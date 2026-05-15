package com.sber.dlmm.pool.client;

import com.sber.dlmm.common.exception.InsufficientBalanceException;
import com.sber.dlmm.common.exception.TokenNotFoundException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

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

    public TokenServiceClient(@Value("${dlmm.services.token-service.url}") String tokenServiceUrl,
                               WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(tokenServiceUrl).build();
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "getTokenFallback")
    @Retry(name = CB_NAME)
    public TokenInfo getToken(UUID tokenId) {
        return webClient.get()
                .uri("/api/v1/tokens/{id}", tokenId)
                .retrieve()
                .onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
                        response -> Mono.error(new TokenNotFoundException("Token not found: " + tokenId)))
                .bodyToMono(TokenInfo.class)
                .block(CALL_TIMEOUT);
    }

    @SuppressWarnings("unused")
    private TokenInfo getTokenFallback(UUID tokenId, Throwable ex) {
        if (ex instanceof TokenNotFoundException) {
            throw (TokenNotFoundException) ex;
        }
        log.error("Token-service circuit OPEN or call failed for tokenId={}: {}", tokenId, ex.toString());
        throw new IllegalStateException("Token service unavailable: " + ex.getMessage(), ex);
    }

    public boolean isTokenActive(UUID tokenId) {
        TokenInfo token = getToken(tokenId);
        return token != null && token.active();
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "deductBalanceFallback")
    @Retry(name = CB_NAME)
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

    @SuppressWarnings("unused")
    private void deductBalanceFallback(UUID userId, UUID tokenId, long amount, Throwable ex) {
        if (ex instanceof InsufficientBalanceException) {
            throw (InsufficientBalanceException) ex;
        }
        log.error("Token-service deduct failed for user={} token={}: {}", userId, tokenId, ex.toString());
        throw new IllegalStateException("Token service unavailable for deduct: " + ex.getMessage(), ex);
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "creditBalanceFallback")
    @Retry(name = CB_NAME)
    public void creditBalance(UUID userId, UUID tokenId, long amount) {
        CreditRequest request = new CreditRequest(userId, tokenId, amount);
        webClient.post()
                .uri("/api/v1/tokens/internal/credit")
                .bodyValue(request)
                .retrieve()
                .toBodilessEntity()
                .block(CALL_TIMEOUT);
    }

    @SuppressWarnings("unused")
    private void creditBalanceFallback(UUID userId, UUID tokenId, long amount, Throwable ex) {
        log.error("Token-service credit failed for user={} token={}: {}", userId, tokenId, ex.toString());
        throw new IllegalStateException("Token service unavailable for credit: " + ex.getMessage(), ex);
    }

    public record TokenInfo(UUID id, String name, String symbol, boolean active) {
    }

    public record DeductRequest(UUID userId, UUID tokenId, long amount) {
    }

    public record CreditRequest(UUID userId, UUID tokenId, long amount) {
    }
}
