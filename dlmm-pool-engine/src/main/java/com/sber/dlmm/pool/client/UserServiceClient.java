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

    public UserServiceClient(@Value("${dlmm.services.user-service.url:http://localhost:8081}") String userServiceUrl,
                              WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(userServiceUrl).build();
    }

    @CircuitBreaker(name = CB_NAME, fallbackMethod = "isUserKycVerifiedFallback")
    public boolean isUserKycVerified(UUID userId) {
        UserKycResponse response = webClient.get()
                .uri("/api/v1/users/internal/{userId}/kyc", userId)
                .retrieve()
                .bodyToMono(UserKycResponse.class)
                .block(CALL_TIMEOUT);
        return response != null && response.verified();
    }

    @SuppressWarnings("unused")
    private boolean isUserKycVerifiedFallback(UUID userId, Throwable ex) {
        log.warn("KYC check failed-closed (treating as not verified) for user={}: {}", userId, ex.toString());
        return false;
    }

    public record UserKycResponse(boolean verified) {}
}
