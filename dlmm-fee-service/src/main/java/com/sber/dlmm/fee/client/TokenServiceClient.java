package com.sber.dlmm.fee.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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
     * POST /api/v1/tokens/internal/credit. Wrapped in a circuit breaker.
     *
     * <p>NO {@code @Retry} — this is a NON-idempotent money write. Resilience4j
     * {@code @Retry} re-invokes on failure, so a timeout where token-service
     * already committed but the response was lost would re-send and credit the
     * user TWICE (money from nothing). One attempt only; on failure the
     * fallback throws and the {@code @Transactional} fee-claim rolls back, so
     * the user can retry deliberately. Full fix: an idempotency key on the
     * internal credit endpoint so retries dedup — tracked as a followup.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "creditFallback")
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

    /**
     * Circuit-breaker fallback for {@link #credit}: invoked when the breaker is OPEN or
     * the call failed. It deliberately re-throws rather than swallowing — a credit is
     * money, so a failed credit must abort the enclosing {@code @Transactional} fee-claim
     * (rolling back the accrual flip) instead of silently marking fees paid that were
     * never credited. The user can retry once token-service recovers.
     *
     * <p>Referenced by name in {@code @CircuitBreaker(fallbackMethod = "creditFallback")};
     * Resilience4j requires the same parameter list as {@link #credit} plus the trailing
     * {@link Throwable}. {@code @SuppressWarnings("unused")} because it is only called reflectively.
     *
     * @param userId  the user the original credit targeted
     * @param tokenId the token the original credit targeted
     * @param amount  the raw amount the original credit attempted
     * @param ex      the failure (or open-circuit signal) that triggered the fallback
     * @throws IllegalStateException always, to force the fee-claim transaction to roll back
     */
    @SuppressWarnings("unused")
    private void creditFallback(UUID userId, UUID tokenId, long amount, Throwable ex) {
        log.error("Token-service circuit OPEN or call failed on CREDIT "
                + "user={} token={} amount={}: {} — fee-claim row will roll back; "
                + "user can retry once token-service recovers", userId, tokenId, amount, ex.toString());
        throw new IllegalStateException("Token service unavailable for fee-claim credit: " + ex.getMessage(), ex);
    }
}
