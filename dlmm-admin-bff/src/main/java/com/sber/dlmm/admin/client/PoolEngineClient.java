package com.sber.dlmm.admin.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Sprint 8 C-10 (R#33) — first per-downstream client extraction from
 * admin-bff's AdminService god-class.
 *
 * <p>Pool-engine is the most-trafficked admin-bff downstream — every
 * /admin/dashboard load makes two calls (positions/count + pools/list)
 * plus per-pool analytics fan-out. Without a circuit-breaker, a slow
 * or down pool-engine adds 8s × 2 to every dashboard load and saturates
 * the admin-bff thread pool.
 *
 * <p>{@link CircuitBreaker} + {@link Retry} mirror the dlmm-pool-engine
 * reference pattern. CB name {@code pool-engine} matches the
 * resilience4j yaml block so operator dashboards/alerts work the
 * same way across services.
 *
 * <p>The other admin-bff downstreams (user-service, token-service,
 * transaction-service, fee-service, price-oracle) keep the inline
 * WebClient pattern for now — Sprint 9 carry-over per audit C-10.
 * Pool-engine first because (a) most-trafficked, (b) blocking the
 * Dashboard is the worst UX failure.
 */
@Component
public class PoolEngineClient {

    private static final Logger log = LoggerFactory.getLogger(PoolEngineClient.class);
    private static final String CB_NAME = "pool-engine";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(8);

    private final WebClient webClient;

    public PoolEngineClient(@Value("${dlmm.services.pool-engine-url}") String poolEngineUrl,
                             WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(poolEngineUrl).build();
    }

    /**
     * GET /api/v1/pools/positions/count → {@code activePositions} value.
     * Returns 0 on circuit-open or fallback to keep the Dashboard rendering.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "fetchActivePositionsCountFallback")
    @Retry(name = CB_NAME)
    public long fetchActivePositionsCount() {
        Map<String, Object> response = webClient.get()
                .uri("/api/v1/pools/positions/count")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block(CALL_TIMEOUT);
        return response == null ? 0L : toLong(response.get("activePositions"));
    }

    @SuppressWarnings("unused")
    private long fetchActivePositionsCountFallback(Throwable ex) {
        log.warn("pool-engine /positions/count CB OPEN or call failed: {}. Returning 0.", ex.toString());
        return 0L;
    }

    /**
     * GET /api/v1/pools?page=0&size=N → page content list. Used by
     * Dashboard's pool aggregation (TVL, volume, fee totals).
     * Empty list on fallback so the rest of the dashboard renders.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "fetchPoolsPageFallback")
    @Retry(name = CB_NAME)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchPoolsPage(int size) {
        Map<String, Object> page = webClient.get()
                .uri("/api/v1/pools?page=0&size=" + size)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorResume(ex -> {
                    // Fall through to empty Map so the CB sees a "success"
                    // for HTTP-level errors that aren't connection failures —
                    // we don't want a 404 on a missing endpoint to trip the
                    // breaker, only network-level failures should.
                    log.warn("pool-engine /pools fetch returned error: {}", ex.toString());
                    return Mono.empty();
                })
                .block(CALL_TIMEOUT);
        if (page == null) return Collections.emptyList();
        Object content = page.get("content");
        return content instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : Collections.emptyList();
    }

    @SuppressWarnings("unused")
    private List<Map<String, Object>> fetchPoolsPageFallback(int size, Throwable ex) {
        log.warn("pool-engine /pools page (size={}) CB OPEN or call failed: {}. Returning empty.",
                size, ex.toString());
        return Collections.emptyList();
    }

    /**
     * Sprint 9-DS-r4 (P1-13) — GET /api/v1/pools/{id}. Used by
     * {@code AdminService.getPoolAnalytics} for the TVL/volume/bin
     * history series. Empty map on fallback so the caller's
     * {@code extractList(map, ...)} reads short and the page renders
     * the rest of the analytics with whatever else came back.
     */
    @CircuitBreaker(name = CB_NAME, fallbackMethod = "fetchPoolDetailFallback")
    @Retry(name = CB_NAME)
    public Map<String, Object> fetchPoolDetail(java.util.UUID poolId) {
        Map<String, Object> result = webClient.get()
                .uri("/api/v1/pools/{id}", poolId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block(CALL_TIMEOUT);
        return result == null ? Collections.emptyMap() : result;
    }

    @SuppressWarnings("unused")
    private Map<String, Object> fetchPoolDetailFallback(java.util.UUID poolId, Throwable ex) {
        log.warn("pool-engine /pools/{} CB OPEN or call failed: {}. Returning empty.",
                poolId, ex.toString());
        return Collections.emptyMap();
    }

    private static long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
