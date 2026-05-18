package com.sber.dlmm.transaction.client;

import com.sber.dlmm.common.exception.InsufficientBalanceException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.UUID;

/**
 * Sprint 4 #4.6 — minimal RestTemplate client to dlmm-token-service for
 * the B2B settlement flow. Mirrors the methods pool-engine's TokenServiceClient
 * uses, but stays on the servlet stack (transaction-service is webmvc, not
 * webflux — adding webflux would force a web-application-type decision and
 * risk destabilising the existing /report and /transactions endpoints).
 *
 * <p>Bearer-token forwarding via a request interceptor — the inbound
 * Authorization header from the corp operator's call gets copied onto
 * the outbound token-service call so the downstream JwtAuthenticationFilter
 * accepts it. Equivalent to dlmm-common's BearerTokenForwardingFilter
 * for WebClient, just inline because the WebClient variant doesn't apply
 * to RestTemplate.
 *
 * <p>Sprint 8 #C-10: wrapped in Resilience4j (circuit breaker + retry).
 * Tuning lives in application.yml under {@code resilience4j.*.instances.token-service},
 * mirroring the pool-engine reference. Note: timelimiter is NOT applied —
 * RestTemplate already enforces a 5s read timeout via the builder above,
 * and resilience4j-timelimiter requires CompletableFuture return types
 * which this synchronous client doesn't use.
 */
@Component
public class TokenServiceClient {

    private static final Logger log = LoggerFactory.getLogger(TokenServiceClient.class);
    private static final String CB_NAME = "token-service";

    private final RestTemplate restTemplate;
    private final String tokenServiceUrl;

    public TokenServiceClient(@Value("${dlmm.services.token-service.url}") String tokenServiceUrl,
                              RestTemplateBuilder builder) {
        this.tokenServiceUrl = tokenServiceUrl;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .additionalInterceptors(bearerForwardingInterceptor())
                .build();
    }

    /**
     * POST /api/v1/tokens/internal/deduct.
     *
     * @throws InsufficientBalanceException when token-service responds 400
     *         (we treat all 4xx on this endpoint as caller-side; the only
     *         expected 4xx is insufficient balance).
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "deductFallback")
    @Retry(name = CB_NAME)
    public void deduct(UUID userId, UUID tokenId, long amount) {
        InternalBalanceRequest body = new InternalBalanceRequest(userId, tokenId, amount);
        try {
            restTemplate.exchange(tokenServiceUrl + "/api/v1/tokens/internal/deduct",
                    HttpMethod.POST,
                    new HttpEntity<>(body, jsonHeaders()),
                    Void.class);
        } catch (HttpClientErrorException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == HttpStatus.BAD_REQUEST.value()) {
                throw new InsufficientBalanceException(
                        "Token-service rejected deduct for user=" + userId + " token=" + tokenId
                                + " amount=" + amount + ": " + e.getResponseBodyAsString());
            }
            throw e;
        }
    }

    /**
     * POST /api/v1/tokens/internal/credit. Failure here AFTER a successful
     * deduct is the dangerous case — caller (B2BSettlementService) must
     * mark the settlement FAILED with a loud diagnostic so an operator
     * can reconcile manually. Saga / compensating-action design is Sprint 5+.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "creditFallback")
    @Retry(name = CB_NAME)
    public void credit(UUID userId, UUID tokenId, long amount) {
        InternalBalanceRequest body = new InternalBalanceRequest(userId, tokenId, amount);
        restTemplate.exchange(tokenServiceUrl + "/api/v1/tokens/internal/credit",
                HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()),
                Void.class);
    }

    /**
     * Fallback for {@link #deduct(UUID, UUID, long)} — when token-service is
     * circuit-OPEN or all retries exhausted. Re-throws the underlying business
     * exception (insufficient balance) so callers see the correct error;
     * wraps unknown errors as IllegalStateException so the surrounding
     * B2B settlement transaction rolls back instead of silently passing.
     */
    @SuppressWarnings("unused")
    private void deductFallback(UUID userId, UUID tokenId, long amount, Throwable ex) {
        if (ex instanceof InsufficientBalanceException ibe) throw ibe;
        log.error("Token-service circuit OPEN or call failed on deduct user={} token={} amount={}: {}",
                userId, tokenId, amount, ex.toString());
        throw new IllegalStateException("Token service unavailable for deduct: " + ex.getMessage(), ex);
    }

    /**
     * Fallback for {@link #credit(UUID, UUID, long)}. Same pattern; credit
     * failure is the more dangerous case (already deducted, can't credit) so
     * the loud log entry is doubly important — an operator must reconcile.
     */
    @SuppressWarnings("unused")
    private void creditFallback(UUID userId, UUID tokenId, long amount, Throwable ex) {
        log.error("Token-service circuit OPEN or call failed on CREDIT user={} token={} amount={}: {} — "
                + "MANUAL RECONCILIATION required if a prior deduct succeeded", userId, tokenId, amount, ex.toString());
        throw new IllegalStateException("Token service unavailable for credit: " + ex.getMessage(), ex);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    /**
     * Copies the Authorization header from the currently-handling request
     * onto every outbound call. Quiet no-op if there's no servlet request
     * in scope (e.g. scheduled job — none exist yet but future-proof).
     */
    private static ClientHttpRequestInterceptor bearerForwardingInterceptor() {
        return (request, body, execution) -> {
            ServletRequestAttributes attrs = (ServletRequestAttributes)
                    RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                String authHeader = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
                if (authHeader != null && !authHeader.isBlank()
                        && !request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                    request.getHeaders().set(HttpHeaders.AUTHORIZATION, authHeader);
                }
            } else {
                log.debug("No ServletRequestAttributes — outbound call without Bearer (probably out-of-request job)");
            }
            return execution.execute(request, body);
        };
    }

    /**
     * Wire-compatible with token-service's {@code InternalBalanceRequest}.
     * Local copy so transaction-service doesn't depend on dlmm-token-service.
     */
    public record InternalBalanceRequest(UUID userId, UUID tokenId, long amount) {}
}
