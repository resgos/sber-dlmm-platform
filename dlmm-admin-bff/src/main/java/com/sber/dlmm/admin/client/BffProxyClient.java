package com.sber.dlmm.admin.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

/**
 * Sprint 13 S13-01 — circuit-breaker + retry wrappers for the
 * admin-bff's proxy controller. {@link com.sber.dlmm.admin.controller.AdminProxyController}
 * previously held five raw {@link WebClient} fields and ~20 inline calls
 * with only {@code .onErrorReturn(...)} for error handling. That caught
 * HTTP errors but left the thread blocked on a slow downstream until the
 * 10-second timeout fired — a stuck token-service could pin every
 * admin-bff thread on a single proxy endpoint and block the entire admin
 * UI for the full timeout × queue depth.
 *
 * <p>With Resilience4j the CB trips after 50% failures in a 20-call
 * sliding window and fails fast for {@code wait-duration-in-open-state}
 * (10s), giving the bff its threads back.
 *
 * <p>Mirrors {@link BffDownstreamClient}'s pattern — per-downstream CB
 * names match the {@code resilience4j.instances.*} blocks in
 * application.yml so operator dashboards drill into individual services
 * the same way across the codebase. Each method's fallback returns a
 * caller-friendly empty value (empty JSON object / page) plus a loud log
 * line, so the proxy controller's response shape stays valid even on
 * downstream outage.
 *
 * <p>Returning {@code String} keeps the proxy thin — the controller
 * forwards the body unparsed to the admin UI, mirroring the original
 * pre-CB shape. Parsed/transforming responses (e.g. flatten + mint/burn
 * body translation) stay in the controller; this client handles only
 * the network call + CB.
 */
@Component
public class BffProxyClient {

    private static final Logger log = LoggerFactory.getLogger(BffProxyClient.class);

    // Matches the previous AdminProxyController.TIMEOUT (10s). Retry
    // budget per application.yml (3 attempts × 200ms exponential =
    // ~1.4s) fits comfortably inside.
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    static final String EMPTY_PAGE =
            "{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0}";
    static final String EMPTY_OBJECT = "{}";

    private final WebClient userServiceClient;
    private final WebClient tokenServiceClient;
    private final WebClient poolEngineClient;
    private final WebClient transactionServiceClient;

    public BffProxyClient(
            WebClient.Builder webClientBuilder,
            @Value("${dlmm.services.user-service-url}") String userServiceUrl,
            @Value("${dlmm.services.token-service-url}") String tokenServiceUrl,
            @Value("${dlmm.services.pool-engine-url}") String poolEngineUrl,
            @Value("${dlmm.services.transaction-service-url}") String transactionServiceUrl
    ) {
        this.userServiceClient = webClientBuilder.clone().baseUrl(userServiceUrl).build();
        this.tokenServiceClient = webClientBuilder.clone().baseUrl(tokenServiceUrl).build();
        this.poolEngineClient = webClientBuilder.clone().baseUrl(poolEngineUrl).build();
        this.transactionServiceClient = webClientBuilder.clone().baseUrl(transactionServiceUrl).build();
    }

    // ============ USER-SERVICE ============

    @CircuitBreaker(name = "user-service", fallbackMethod = "getUsersFallback")
    @Retry(name = "user-service")
    public String getUsers(int page, int size, String query, String auth) {
        return blocking(userServiceClient.get()
                .uri(u -> {
                    var b = u.path("/api/v1/users")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (query != null && !query.isBlank()) b.queryParam("query", query);
                    return b.build();
                })
                .header("Authorization", safe(auth)),
                EMPTY_PAGE);
    }

    @SuppressWarnings("unused")
    private String getUsersFallback(int page, int size, String query, String auth, Throwable ex) {
        log.warn("user-service /users CB OPEN or call failed: {}", ex.toString());
        return EMPTY_PAGE;
    }

    @CircuitBreaker(name = "user-service", fallbackMethod = "getUserFallback")
    @Retry(name = "user-service")
    public String getUser(UUID id, String auth) {
        return blocking(userServiceClient.get()
                .uri("/api/v1/users/{id}", id)
                .header("Authorization", safe(auth)),
                EMPTY_OBJECT);
    }

    @SuppressWarnings("unused")
    private String getUserFallback(UUID id, String auth, Throwable ex) {
        log.warn("user-service /users/{} CB OPEN or call failed: {}", id, ex.toString());
        return EMPTY_OBJECT;
    }

    @CircuitBreaker(name = "user-service", fallbackMethod = "updateKycFallback")
    @Retry(name = "user-service")
    public String updateKyc(UUID id, String body, String auth) {
        return blockingBody(userServiceClient.put()
                .uri("/api/v1/users/{id}/kyc", id)
                .header("Authorization", safe(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body),
                EMPTY_OBJECT);
    }

    @SuppressWarnings("unused")
    private String updateKycFallback(UUID id, String body, String auth, Throwable ex) {
        log.warn("user-service /users/{}/kyc CB OPEN or call failed: {}", id, ex.toString());
        return EMPTY_OBJECT;
    }

    @CircuitBreaker(name = "user-service", fallbackMethod = "updateRoleFallback")
    @Retry(name = "user-service")
    public String updateRole(UUID id, String body, String auth) {
        return blockingBody(userServiceClient.put()
                .uri("/api/v1/users/{id}/role", id)
                .header("Authorization", safe(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body),
                EMPTY_OBJECT);
    }

    @SuppressWarnings("unused")
    private String updateRoleFallback(UUID id, String body, String auth, Throwable ex) {
        log.warn("user-service /users/{}/role CB OPEN or call failed: {}", id, ex.toString());
        return EMPTY_OBJECT;
    }

    @CircuitBreaker(name = "user-service", fallbackMethod = "blockUserFallback")
    @Retry(name = "user-service")
    public void blockUser(UUID id, String auth) {
        userServiceClient.post()
                .uri("/api/v1/users/{id}/block", id)
                .header("Authorization", safe(auth))
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> Mono.empty())
                .block(CALL_TIMEOUT);
    }

    @SuppressWarnings("unused")
    private void blockUserFallback(UUID id, String auth, Throwable ex) {
        log.warn("user-service /users/{}/block CB OPEN or call failed: {}", id, ex.toString());
    }

    @CircuitBreaker(name = "user-service", fallbackMethod = "unblockUserFallback")
    @Retry(name = "user-service")
    public void unblockUser(UUID id, String auth) {
        userServiceClient.post()
                .uri("/api/v1/users/{id}/unblock", id)
                .header("Authorization", safe(auth))
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> Mono.empty())
                .block(CALL_TIMEOUT);
    }

    @SuppressWarnings("unused")
    private void unblockUserFallback(UUID id, String auth, Throwable ex) {
        log.warn("user-service /users/{}/unblock CB OPEN or call failed: {}", id, ex.toString());
    }

    // ============ TRANSACTION-SERVICE ============

    @CircuitBreaker(name = "transaction-service", fallbackMethod = "getUserTransactionsFallback")
    @Retry(name = "transaction-service")
    public String getUserTransactions(UUID id, int page, int size, String auth) {
        return blocking(transactionServiceClient.get()
                .uri(u -> u.path("/api/v1/transactions/user/{userId}")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build(id))
                .header("Authorization", safe(auth)),
                EMPTY_PAGE);
    }

    @SuppressWarnings("unused")
    private String getUserTransactionsFallback(UUID id, int page, int size, String auth, Throwable ex) {
        log.warn("transaction-service /transactions/user/{} CB OPEN or call failed: {}",
                id, ex.toString());
        return EMPTY_PAGE;
    }

    @CircuitBreaker(name = "transaction-service", fallbackMethod = "getTransactionsFallback")
    @Retry(name = "transaction-service")
    public String getTransactions(int page, int size, String txType, String status, String auth) {
        return blocking(transactionServiceClient.get()
                .uri(u -> {
                    var b = u.path("/api/v1/transactions")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (txType != null) b.queryParam("type", txType);
                    if (status != null) b.queryParam("status", status);
                    return b.build();
                })
                .header("Authorization", safe(auth)),
                EMPTY_PAGE);
    }

    @SuppressWarnings("unused")
    private String getTransactionsFallback(int page, int size, String txType, String status,
                                           String auth, Throwable ex) {
        log.warn("transaction-service /transactions CB OPEN or call failed: {}", ex.toString());
        return EMPTY_PAGE;
    }

    @CircuitBreaker(name = "transaction-service", fallbackMethod = "reviewTransactionFallback")
    @Retry(name = "transaction-service")
    public String reviewTransaction(UUID id, String auth) {
        return blocking(transactionServiceClient.post()
                .uri("/api/v1/transactions/{id}/review", id)
                .header("Authorization", safe(auth)),
                "{\"error\":\"Failed to mark reviewed\"}");
    }

    @SuppressWarnings("unused")
    private String reviewTransactionFallback(UUID id, String auth, Throwable ex) {
        log.warn("transaction-service /transactions/{}/review CB OPEN or call failed: {}",
                id, ex.toString());
        return "{\"error\":\"Failed to mark reviewed\"}";
    }

    // ============ POOL-ENGINE ============

    @CircuitBreaker(name = "pool-engine", fallbackMethod = "getPoolsFallback")
    @Retry(name = "pool-engine")
    public String getPools(int page, int size, String auth) {
        return blocking(poolEngineClient.get()
                .uri(u -> u.path("/api/v1/pools")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build())
                .header("Authorization", safe(auth)),
                EMPTY_PAGE);
    }

    @SuppressWarnings("unused")
    private String getPoolsFallback(int page, int size, String auth, Throwable ex) {
        log.warn("pool-engine /pools CB OPEN or call failed: {}", ex.toString());
        return EMPTY_PAGE;
    }

    /**
     * Returns the raw JSON body so the controller can flatten the
     * nested {@code pool} object. Returns {@code null} on absence so
     * the controller maps that to a 404. The CB-OPEN fallback also
     * returns {@code null} — admin UI then surfaces "pool not found"
     * which is the safest UX when pool-engine is wedged (better than
     * an empty object that would pretend the pool exists).
     */
    @CircuitBreaker(name = "pool-engine", fallbackMethod = "getPoolFallback")
    @Retry(name = "pool-engine")
    public String getPool(UUID id, String auth) {
        return poolEngineClient.get()
                .uri("/api/v1/pools/{id}", id)
                .header("Authorization", safe(auth))
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.empty())
                .block(CALL_TIMEOUT);
    }

    @SuppressWarnings("unused")
    private String getPoolFallback(UUID id, String auth, Throwable ex) {
        log.warn("pool-engine /pools/{} CB OPEN or call failed: {}", id, ex.toString());
        return null;
    }

    @CircuitBreaker(name = "pool-engine", fallbackMethod = "createPoolFallback")
    @Retry(name = "pool-engine")
    public String createPool(String body, String auth) {
        return blockingBody(poolEngineClient.post()
                .uri("/api/v1/pools")
                .header("Authorization", safe(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body),
                "{\"error\":\"Failed to create pool\"}");
    }

    @SuppressWarnings("unused")
    private String createPoolFallback(String body, String auth, Throwable ex) {
        log.warn("pool-engine POST /pools CB OPEN or call failed: {}", ex.toString());
        return "{\"error\":\"Failed to create pool\"}";
    }

    @CircuitBreaker(name = "pool-engine", fallbackMethod = "poolActionFallback")
    @Retry(name = "pool-engine")
    public String pausePool(UUID id, String auth) {
        return blocking(poolEngineClient.post()
                .uri("/api/v1/pools/{id}/pause", id)
                .header("Authorization", safe(auth)),
                EMPTY_OBJECT);
    }

    @CircuitBreaker(name = "pool-engine", fallbackMethod = "poolActionFallback")
    @Retry(name = "pool-engine")
    public String resumePool(UUID id, String auth) {
        return blocking(poolEngineClient.post()
                .uri("/api/v1/pools/{id}/resume", id)
                .header("Authorization", safe(auth)),
                EMPTY_OBJECT);
    }

    @CircuitBreaker(name = "pool-engine", fallbackMethod = "poolActionFallback")
    @Retry(name = "pool-engine")
    public String emergencyShutdown(UUID id, String auth) {
        return blocking(poolEngineClient.post()
                .uri("/api/v1/pools/{id}/emergency-shutdown", id)
                .header("Authorization", safe(auth)),
                EMPTY_OBJECT);
    }

    @SuppressWarnings("unused")
    private String poolActionFallback(UUID id, String auth, Throwable ex) {
        log.warn("pool-engine action on pool {} CB OPEN or call failed: {}", id, ex.toString());
        return EMPTY_OBJECT;
    }

    // ============ TOKEN-SERVICE ============

    @CircuitBreaker(name = "token-service", fallbackMethod = "getTokensFallback")
    @Retry(name = "token-service")
    public String getTokens(int page, int size, String auth) {
        return blocking(tokenServiceClient.get()
                .uri(u -> u.path("/api/v1/tokens")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build())
                .header("Authorization", safe(auth)),
                EMPTY_PAGE);
    }

    @SuppressWarnings("unused")
    private String getTokensFallback(int page, int size, String auth, Throwable ex) {
        log.warn("token-service /tokens CB OPEN or call failed: {}", ex.toString());
        return EMPTY_PAGE;
    }

    @CircuitBreaker(name = "token-service", fallbackMethod = "getTokenFallback")
    @Retry(name = "token-service")
    public String getToken(UUID id, String auth) {
        return blocking(tokenServiceClient.get()
                .uri("/api/v1/tokens/{id}", id)
                .header("Authorization", safe(auth)),
                EMPTY_OBJECT);
    }

    @SuppressWarnings("unused")
    private String getTokenFallback(UUID id, String auth, Throwable ex) {
        log.warn("token-service /tokens/{} CB OPEN or call failed: {}", id, ex.toString());
        return EMPTY_OBJECT;
    }

    @CircuitBreaker(name = "token-service", fallbackMethod = "createTokenFallback")
    @Retry(name = "token-service")
    public String createToken(String body, String auth) {
        return blockingBody(tokenServiceClient.post()
                .uri("/api/v1/tokens")
                .header("Authorization", safe(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body),
                "{\"error\":\"Failed to create token\"}");
    }

    @SuppressWarnings("unused")
    private String createTokenFallback(String body, String auth, Throwable ex) {
        log.warn("token-service POST /tokens CB OPEN or call failed: {}", ex.toString());
        return "{\"error\":\"Failed to create token\"}";
    }

    /**
     * Pre-transformed body (controller massages mint/burn request shape).
     * Returns the raw JSON so the controller forwards as-is.
     */
    @CircuitBreaker(name = "token-service", fallbackMethod = "mintTokenFallback")
    @Retry(name = "token-service")
    public String mintToken(String transformedBody, String auth) {
        return blockingBody(tokenServiceClient.post()
                .uri("/api/v1/tokens/mint")
                .header("Authorization", safe(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(transformedBody),
                "{\"error\":\"Mint failed\"}");
    }

    @SuppressWarnings("unused")
    private String mintTokenFallback(String transformedBody, String auth, Throwable ex) {
        log.warn("token-service /tokens/mint CB OPEN or call failed: {}", ex.toString());
        return "{\"error\":\"Mint failed\"}";
    }

    @CircuitBreaker(name = "token-service", fallbackMethod = "burnTokenFallback")
    @Retry(name = "token-service")
    public String burnToken(String transformedBody, String auth) {
        return blockingBody(tokenServiceClient.post()
                .uri("/api/v1/tokens/burn")
                .header("Authorization", safe(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(transformedBody),
                "{\"error\":\"Burn failed\"}");
    }

    @SuppressWarnings("unused")
    private String burnTokenFallback(String transformedBody, String auth, Throwable ex) {
        log.warn("token-service /tokens/burn CB OPEN or call failed: {}", ex.toString());
        return "{\"error\":\"Burn failed\"}";
    }

    // ============ HELPERS ============

    /**
     * Standard GET / POST-without-body shape — retrieve the response,
     * map HTTP-level errors to the supplied fallback string (so CB
     * doesn't trip on a 404 from a typo), then block.
     */
    private String blocking(WebClient.RequestHeadersSpec<?> spec, String onError) {
        return spec.retrieve()
                .bodyToMono(String.class)
                .onErrorResume(swallowHttp(onError))
                .block(CALL_TIMEOUT);
    }

    /**
     * Same as {@link #blocking} but for {@link WebClient.RequestBodySpec}
     * (PUT / POST with body). Spring's WebClient builder splits the
     * two interfaces just enough that one helper can't cover both
     * without casting.
     */
    private String blockingBody(WebClient.RequestHeadersSpec<?> spec, String onError) {
        return spec.retrieve()
                .bodyToMono(String.class)
                .onErrorResume(swallowHttp(onError))
                .block(CALL_TIMEOUT);
    }

    /**
     * HTTP-level errors (4xx/5xx from downstream) shouldn't trip the
     * CB — only network/timeout failures should. Surface as the
     * fallback body so Retry sees a "success" and the controller
     * forwards the canned error to the UI.
     */
    private static Function<Throwable, Mono<String>> swallowHttp(String fallback) {
        return ex -> {
            log.debug("Downstream HTTP error swallowed: {}", ex.toString());
            return Mono.just(fallback);
        };
    }

    private static String safe(String auth) {
        return auth != null ? auth : "";
    }
}
