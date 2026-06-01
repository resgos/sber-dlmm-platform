package com.sber.dlmm.pool.client;

import com.sber.dlmm.common.exception.InsufficientBalanceException;
import com.sber.dlmm.common.exception.TokenNotFoundException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Inter-service client to dlmm-token-service. Wrapped in Resilience4j
 * circuit breaker + retry + per-call timeout so a slow or failing token
 * service degrades gracefully instead of cascading into pool-engine.
 *
 * Tuning lives in application.yml under {@code resilience4j.*.instances.token-service}.
 */
@Component
public class TokenServiceClient {

    private static final Logger log = LoggerFactory.getLogger(TokenServiceClient.class);
    private static final String CB_NAME = "token-service";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(3);

    private final WebClient webClient;

    /**
     * Short-lived LRU cache for token info. Tokens are rarely mutated (mostly
     * only on admin mint/burn/pause), so a 60-second TTL gives huge perf wins
     * for hot paths (every swap quote resolves both tokens) at acceptable
     * staleness. Bounded to 500 entries — fits the seed catalog ~25× over.
     */
    private final Cache<UUID, TokenInfo> tokenCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    /**
     * Builds the WebClient pinned to the token-service base URL.
     *
     * @param tokenServiceUrl  token-service base URL (from {@code dlmm.services.token-service.url})
     * @param webClientBuilder shared, auto-configured builder (carries bearer-token forwarding) used to create the client
     */
    public TokenServiceClient(@Value("${dlmm.services.token-service.url}") String tokenServiceUrl,
                               WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(tokenServiceUrl).build();
    }

    /**
     * Resolves a single token's info, serving from the 60s Caffeine cache on a
     * hit and otherwise fetching from token-service and caching the result.
     * Idempotent read, so it is retried; a 404 is mapped to
     * {@link TokenNotFoundException} and re-thrown rather than retried.
     *
     * @param tokenId the token to resolve
     * @return the token info (id, name, symbol, active)
     * @throws TokenNotFoundException if token-service reports the token does not exist
     * @throws IllegalStateException  if token-service is unavailable (circuit open / call failed)
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "getTokenFallback")
    @Retry(name = CB_NAME)
    public TokenInfo getToken(UUID tokenId) {
        TokenInfo cached = tokenCache.getIfPresent(tokenId);
        if (cached != null) return cached;
        TokenInfo fetched = webClient.get()
                .uri("/api/v1/tokens/{id}", tokenId)
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                        response -> Mono.error(new TokenNotFoundException("Token not found: " + tokenId)))
                .bodyToMono(TokenInfo.class)
                .block(CALL_TIMEOUT);
        if (fetched != null) tokenCache.put(tokenId, fetched);
        return fetched;
    }

    /**
     * Circuit-breaker fallback for {@link #getToken}. Re-throws a genuine
     * {@link TokenNotFoundException} (a 404 is a real answer, not an outage);
     * any other failure surfaces as {@link IllegalStateException} so the
     * caller's transaction rolls back rather than proceeding on stale data.
     *
     * @param tokenId the token that was being resolved
     * @param ex      the failure that tripped the breaker
     * @return never returns normally — always throws
     * @throws TokenNotFoundException if the underlying failure was a 404
     * @throws IllegalStateException  for any other token-service failure
     */
    @SuppressWarnings("unused")
    private TokenInfo getTokenFallback(UUID tokenId, Throwable ex) {
        if (ex instanceof TokenNotFoundException) {
            throw (TokenNotFoundException) ex;
        }
        log.error("Token-service circuit OPEN or call failed for tokenId={}: {}", tokenId, ex.toString());
        throw new IllegalStateException("Token service unavailable: " + ex.getMessage(), ex);
    }

    /**
     * Convenience predicate over {@link #getToken}: is the token tradable?
     * Used to reject swaps/liquidity against paused or unknown tokens.
     *
     * @param tokenId the token to check
     * @return {@code true} if the token exists and is active
     * @throws TokenNotFoundException if the token does not exist
     * @throws IllegalStateException  if token-service is unavailable
     */
    public boolean isTokenActive(UUID tokenId) {
        TokenInfo token = getToken(tokenId);
        return token != null && token.active();
    }

    /**
     * Bulk symbol/name resolution — used by PoolService.getAllPools to fill in
     * tokenXSymbol/tokenYSymbol on every PoolResponse with one network call
     * instead of one per pool side.
     *
     * On any failure (circuit open, timeout, network) the fallback returns an
     * empty map — the caller treats every symbol as null, the list still
     * renders, only the labels are missing.
     *
     * @param ids the token ids to resolve (null or empty yields an empty map)
     * @return a map from token id to {@link TokenInfo}; ids that token-service
     *         does not return are simply absent from the map
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "getTokensByIdsFallback")
    @Retry(name = CB_NAME)
    public Map<UUID, TokenInfo> getTokensByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();

        // Serve already-cached ids without a round-trip.
        Map<UUID, TokenInfo> result = new HashMap<>(ids.size() * 2);
        List<UUID> missing = new ArrayList<>();
        for (UUID id : ids) {
            TokenInfo cached = tokenCache.getIfPresent(id);
            if (cached != null) result.put(id, cached);
            else missing.add(id);
        }
        if (missing.isEmpty()) return result;

        String csv = missing.stream().map(UUID::toString).collect(Collectors.joining(","));
        List<TokenInfo> fetched = webClient.get()
                .uri(uri -> uri.path("/api/v1/tokens/batch").queryParam("ids", csv).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<TokenInfo>>() {})
                .block(CALL_TIMEOUT);
        if (fetched != null) {
            for (TokenInfo t : fetched) {
                tokenCache.put(t.id(), t);
                result.put(t.id(), t);
            }
        }
        return result;
    }

    /**
     * Circuit-breaker fallback for {@link #getTokensByIds}. Degrades softly —
     * logs and returns an empty map so the caller renders the list without
     * symbol labels rather than failing the whole request.
     *
     * @param ids the ids that were being resolved
     * @param ex  the failure that tripped the breaker
     * @return always an empty map
     */
    @SuppressWarnings("unused")
    private Map<UUID, TokenInfo> getTokensByIdsFallback(Collection<UUID> ids, Throwable ex) {
        log.warn("Token-service batch lookup failed ({} ids): {}", ids == null ? 0 : ids.size(), ex.toString());
        return Collections.emptyMap();
    }

    /**
     * Debits a user's token balance via token-service's internal endpoint —
     * the money-moving leg of a swap/add-liquidity. Deliberately NOT retried
     * (non-idempotent write): a retry after a lost-response timeout would
     * double-deduct, so a single attempt is made and any failure rolls back
     * the caller's surrounding transaction.
     *
     * @param userId  the user whose balance is debited
     * @param tokenId the token to debit
     * @param amount  raw amount to deduct (1 unit = 10⁻⁴ token)
     * @throws InsufficientBalanceException if token-service rejects the deduct (400, balance too low)
     * @throws IllegalStateException        if token-service is unavailable
     */
    // NO @Retry — non-idempotent balance write. A retry after a lost-response
    // timeout (token-service committed, response dropped) double-deducts the
    // user. One attempt; failure → fallback throws → swap @Transactional rolls
    // back. The idempotent read methods above keep @Retry. Full fix:
    // idempotency key on /tokens/internal/deduct (followup).
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "deductBalanceFallback")
    public void deductBalance(UUID userId, UUID tokenId, long amount) {
        DeductRequest request = new DeductRequest(userId, tokenId, amount);
        webClient.post()
                .uri("/api/v1/tokens/internal/deduct")
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.BAD_REQUEST.value(),
                        response -> Mono.error(new InsufficientBalanceException(
                                "Insufficient balance for user " + userId + " token " + tokenId)))
                .toBodilessEntity()
                .block(CALL_TIMEOUT);
    }

    /**
     * Circuit-breaker fallback for {@link #deductBalance}. Re-throws a genuine
     * {@link InsufficientBalanceException} (a real business rejection, not an
     * outage); any other failure surfaces as {@link IllegalStateException} so
     * the caller's transaction rolls back and no balance is moved.
     *
     * @param userId  the user that was being debited
     * @param tokenId the token that was being debited
     * @param amount  the amount that was being deducted
     * @param ex      the failure that tripped the breaker
     * @throws InsufficientBalanceException if the underlying failure was an insufficient-balance rejection
     * @throws IllegalStateException        for any other token-service failure
     */
    @SuppressWarnings("unused")
    private void deductBalanceFallback(UUID userId, UUID tokenId, long amount, Throwable ex) {
        if (ex instanceof InsufficientBalanceException) {
            throw (InsufficientBalanceException) ex;
        }
        log.error("Token-service deduct failed for user={} token={}: {}", userId, tokenId, ex.toString());
        throw new IllegalStateException("Token service unavailable for deduct: " + ex.getMessage(), ex);
    }

    /**
     * Credits a user's token balance via token-service's internal endpoint —
     * the payout leg of a swap/remove-liquidity. Deliberately NOT retried
     * (non-idempotent write): a retry after a lost-response timeout would
     * double-credit, so a single attempt is made and any failure rolls back
     * the caller's surrounding transaction.
     *
     * @param userId  the user whose balance is credited
     * @param tokenId the token to credit
     * @param amount  raw amount to credit (1 unit = 10⁻⁴ token)
     * @throws IllegalStateException if token-service is unavailable
     */
    // NO @Retry — non-idempotent balance write (see deductBalance); a lost-
    // response retry would double-credit. Full fix: idempotency key (followup).
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "creditBalanceFallback")
    public void creditBalance(UUID userId, UUID tokenId, long amount) {
        CreditRequest request = new CreditRequest(userId, tokenId, amount);
        webClient.post()
                .uri("/api/v1/tokens/internal/credit")
                .bodyValue(request)
                .retrieve()
                .toBodilessEntity()
                .block(CALL_TIMEOUT);
    }

    /**
     * Circuit-breaker fallback for {@link #creditBalance}. Logs and surfaces
     * the failure as {@link IllegalStateException} so the caller's transaction
     * rolls back rather than silently dropping a credit.
     *
     * @param userId  the user that was being credited
     * @param tokenId the token that was being credited
     * @param amount  the amount that was being credited
     * @param ex      the failure that tripped the breaker
     * @throws IllegalStateException always, wrapping the cause
     */
    @SuppressWarnings("unused")
    private void creditBalanceFallback(UUID userId, UUID tokenId, long amount, Throwable ex) {
        log.error("Token-service credit failed for user={} token={}: {}", userId, tokenId, ex.toString());
        throw new IllegalStateException("Token service unavailable for credit: " + ex.getMessage(), ex);
    }

    /**
     * Token metadata returned by token-service and cached locally.
     *
     * @param id     the token's unique id
     * @param name   human-readable token name
     * @param symbol ticker symbol (e.g. SRUB, SBTC)
     * @param active whether the token is currently tradable
     */
    public record TokenInfo(UUID id, String name, String symbol, boolean active) {
    }

    /**
     * Request body for the internal balance-deduct call.
     *
     * @param userId  the user to debit
     * @param tokenId the token to debit
     * @param amount  raw amount to deduct (1 unit = 10⁻⁴ token)
     */
    public record DeductRequest(UUID userId, UUID tokenId, long amount) {
    }

    /**
     * Request body for the internal balance-credit call.
     *
     * @param userId  the user to credit
     * @param tokenId the token to credit
     * @param amount  raw amount to credit (1 unit = 10⁻⁴ token)
     */
    public record CreditRequest(UUID userId, UUID tokenId, long amount) {
    }
}
