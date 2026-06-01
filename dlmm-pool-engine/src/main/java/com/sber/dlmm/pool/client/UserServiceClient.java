package com.sber.dlmm.pool.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.UUID;

/**
 * Inter-service client to dlmm-user-service. Used for KYC verification
 * before sensitive operations (swap, add-liquidity).
 *
 * Previously this lived inside {@link TokenServiceClient} and called
 * /api/v1/users/internal/{id}/kyc through the token-service base URL —
 * which silently returned 404 (token-service has no such endpoint),
 * the WebClient onErrorReturn fell back to verified=false, and every
 * swap got rejected as "User KYC not verified". This client points at
 * the actual user-service.
 *
 * Wrapped in a circuit breaker; failure mode is fail-CLOSED (treats user
 * as not verified) so a temporary user-service outage cannot grant a
 * swap to an unverified caller.
 */
@Component
public class UserServiceClient {

    private static final Logger log = LoggerFactory.getLogger(UserServiceClient.class);
    private static final String CB_NAME = "user-service";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(3);

    private final WebClient webClient;

    /**
     * Builds the WebClient pinned to the user-service base URL.
     *
     * @param userServiceUrl   user-service base URL (from {@code dlmm.services.user-service.url}, defaults to localhost:8081)
     * @param webClientBuilder shared, auto-configured builder (carries bearer-token forwarding) used to create the client
     */
    public UserServiceClient(@Value("${dlmm.services.user-service.url:http://localhost:8081}") String userServiceUrl,
                              WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(userServiceUrl).build();
    }

    /**
     * Checks whether a user has completed KYC. Called before sensitive
     * operations (swap, add-liquidity) to gate unverified users.
     *
     * <p>Fail-CLOSED: any user-service failure trips the circuit breaker to
     * {@link #isUserKycVerifiedFallback}, which returns {@code false}, so an
     * outage can never let an unverified caller through.
     *
     * @param userId the user to check
     * @return {@code true} only if user-service reports the user as KYC-verified
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "isUserKycVerifiedFallback")
    public boolean isUserKycVerified(UUID userId) {
        UserKycResponse response = webClient.get()
                .uri("/api/v1/users/internal/{userId}/kyc", userId)
                .retrieve()
                .bodyToMono(UserKycResponse.class)
                .block(CALL_TIMEOUT);
        return response != null && response.verified();
    }

    /**
     * Circuit-breaker fallback for {@link #isUserKycVerified}. Fails CLOSED —
     * logs the cause and returns {@code false} so an outage denies rather than
     * grants access to unverified callers.
     *
     * @param userId the user that was being checked
     * @param ex     the failure that tripped the breaker
     * @return always {@code false} (treat as not verified)
     */
    @SuppressWarnings("unused")
    private boolean isUserKycVerifiedFallback(UUID userId, Throwable ex) {
        log.warn("KYC check failed-closed (treating as not verified) for user={}: {}", userId, ex.toString());
        return false;
    }

    /**
     * Sprint 6 #6.7 — checks the 115-ФЗ самозапрет state. Fail-OPEN here
     * (treats user as NOT restricted on user-service outage) because we
     * prefer false-negatives over user lockout: a restriction that fails
     * to enforce ONE swap during an outage is recoverable, but mass user
     * lockout during outage is a P0 incident. Trade-off accepted by
     * Compliance in Sprint 6 #6.7 review.
     *
     * @param userId the user to check
     * @return {@code true} if user-service reports an active 115-ФЗ
     *         self-restriction; {@code false} otherwise or on outage (fail-open)
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "isUserSelfRestrictedFallback")
    public boolean isUserSelfRestricted(UUID userId) {
        SelfRestrictionResponse response = webClient.get()
                .uri("/api/v1/users/internal/{userId}/self-restriction-active", userId)
                .retrieve()
                .bodyToMono(SelfRestrictionResponse.class)
                .block(CALL_TIMEOUT);
        return response != null && response.active();
    }

    /**
     * Circuit-breaker fallback for {@link #isUserSelfRestricted}. Fails OPEN —
     * logs the cause and returns {@code false} (not restricted), preferring a
     * recoverable false-negative over mass user lockout during an outage.
     *
     * @param userId the user that was being checked
     * @param ex     the failure that tripped the breaker
     * @return always {@code false} (treat as not restricted)
     */
    @SuppressWarnings("unused")
    private boolean isUserSelfRestrictedFallback(UUID userId, Throwable ex) {
        log.warn("Self-restriction check failed-OPEN (treating as not restricted) for user={}: {}",
                userId, ex.toString());
        return false;
    }

    /**
     * Response body for the internal KYC lookup.
     *
     * @param verified whether the user has completed KYC verification
     */
    public record UserKycResponse(boolean verified) {}

    /**
     * Response body for the internal 115-ФЗ self-restriction lookup.
     *
     * @param active whether the user currently has an active self-restriction
     */
    public record SelfRestrictionResponse(boolean active) {}
}
