package com.sber.dlmm.fee.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sprint 8 C-10 (R#33) — wraps the outbound /tokens/internal/credit call from
 * fee-service with circuit breaker + retry.
 *
 * <p>Extracted out of {@code FeeService.creditUserBalance} (private method)
 * because Spring AOP only intercepts public methods on Spring-managed beans —
 * a {@code @CircuitBreaker} on a same-class private call would never weave.
 *
 * <p>Mirrors the pool-engine reference pattern: same CB_NAME ("token-service")
 * so the resilience4j.yml block is identically tuneable across services.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TokenServiceClient {

    private static final String CB_NAME = "token-service";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(3);

    // Injected by bean name — see WebClientConfig.tokenServiceWebClient.
    // Field renamed alongside the bean to keep the same wiring intent
    // (resolve naming clash with this @Component class).
    private final WebClient tokenServiceWebClient;

    /**
     * POST /api/v1/internal/credit. Wrapped in CB + Retry. On exhaustion
     * the fallback throws IllegalStateException to bubble up to FeeService —
     * which is wrapped in a @Transactional, so the fee-claim row rollback
     * is automatic. Loud log entry highlights the row that needs manual
     * follow-up.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "creditFallback")
    @Retry(name = CB_NAME)
    public void credit(UUID userId, UUID tokenId, long amount) {
        if (tokenId == null || amount <= 0) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId.toString());
        body.put("tokenId", tokenId.toString());
        body.put("amount", amount);

        // Sprint 9-DS-r2 — was `/api/v1/internal/credit`. Real endpoint is
        // `/api/v1/tokens/internal/credit` (TokenController uses base
        // `/api/v1`, method `@PostMapping("/tokens/internal/credit")`).
        // The wrong path returned 403 because nothing matched and the
        // security chain fell through. Every fee-claim therefore failed.
        tokenServiceWebClient.post()
                .uri("/api/v1/tokens/internal/credit")
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block(CALL_TIMEOUT);
        log.info("Credited {} of token {} to user {}", amount, tokenId, userId);
    }

    @SuppressWarnings("unused")
    private void creditFallback(UUID userId, UUID tokenId, long amount, Throwable ex) {
        log.error("Token-service circuit OPEN or call failed on CREDIT "
                + "user={} token={} amount={}: {} — fee-claim row will roll back; "
                + "user can retry once token-service recovers", userId, tokenId, amount, ex.toString());
        throw new IllegalStateException("Token service unavailable for fee-claim credit: " + ex.getMessage(), ex);
    }
}
