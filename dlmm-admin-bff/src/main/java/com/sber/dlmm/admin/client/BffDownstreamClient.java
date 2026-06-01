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
import java.util.UUID;

/**
 * Sprint 9-DS-r4 (P1-13) — circuit-breaker + retry wrappers for the
 * admin-bff's five non-pool-engine downstream calls. Mirrors the
 * {@link PoolEngineClient} pattern; consolidated into one class because
 * each method is a thin wrapper over a single GET. Per-downstream CB
 * names (and matching resilience4j.instances.* blocks in application.yml)
 * keep operator dashboards able to drill into individual services.
 *
 * <p>Before this extraction, {@code AdminService} held five raw
 * {@link WebClient} fields and called them inline with
 * {@code .onErrorReturn(Collections.emptyList())}. That caught HTTP
 * errors but left the thread blocked on a slow downstream until the
 * 8-second timeout fired — a stuck token-service could pin every
 * admin-bff thread on the analytics endpoint. With Resilience4j the
 * CB trips after 50% failures in a 20-call sliding window and fails
 * fast for the wait-duration, giving the bff its threads back.
 *
 * <p>Each method's fallback returns an empty result of the same shape
 * the caller already expects, so the AdminService aggregation code
 * stays unchanged.
 */
@Component
public class BffDownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(BffDownstreamClient.class);

    // Same 8s budget AdminService used previously. Resilience4j retry
    // budget is configured via application.yml (3 attempts × 200ms
    // backoff = ~1.4s of retry overhead, well inside this timeout).
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(8);

    private final WebClient userServiceWebClient;
    private final WebClient tokenServiceWebClient;
    private final WebClient feeServiceWebClient;
    private final WebClient transactionServiceWebClient;
    private final WebClient priceOracleWebClient;

    /**
     * Builds one base-URL-pinned {@link WebClient} per downstream (cloning the
     * shared bearer-forwarding builder so each carries the caller's
     * {@code Authorization}).
     *
     * @param webClientBuilder      shared, bearer-forwarding builder; cloned per downstream
     * @param userServiceUrl        user-service base URL ({@code dlmm.services.user-service-url})
     * @param tokenServiceUrl       token-service base URL ({@code dlmm.services.token-service-url})
     * @param feeServiceUrl         fee-service base URL ({@code dlmm.services.fee-service-url})
     * @param transactionServiceUrl transaction-service base URL ({@code dlmm.services.transaction-service-url})
     * @param priceOracleUrl        price-oracle base URL ({@code dlmm.services.price-oracle-url})
     */
    public BffDownstreamClient(
            WebClient.Builder webClientBuilder,
            @Value("${dlmm.services.user-service-url}") String userServiceUrl,
            @Value("${dlmm.services.token-service-url}") String tokenServiceUrl,
            @Value("${dlmm.services.fee-service-url}") String feeServiceUrl,
            @Value("${dlmm.services.transaction-service-url}") String transactionServiceUrl,
            @Value("${dlmm.services.price-oracle-url}") String priceOracleUrl
    ) {
        this.userServiceWebClient = webClientBuilder.clone().baseUrl(userServiceUrl).build();
        this.tokenServiceWebClient = webClientBuilder.clone().baseUrl(tokenServiceUrl).build();
        this.feeServiceWebClient = webClientBuilder.clone().baseUrl(feeServiceUrl).build();
        this.transactionServiceWebClient = webClientBuilder.clone().baseUrl(transactionServiceUrl).build();
        this.priceOracleWebClient = webClientBuilder.clone().baseUrl(priceOracleUrl).build();
    }

    // ── user-service ──────────────────────────────────────────────

    /**
     * GET the first page of users from user-service (used by the dashboard's
     * total / verified-user counts). Page content is unwrapped via
     * {@link #fetchPageContent}, whose inline HTTP-error swallow keeps 4xx/5xx
     * from tripping the breaker.
     *
     * @param size page size to request (page index fixed at 0)
     * @return the page's user maps, or empty on error / CB-open (via
     *         {@link #fetchUsersPageFallback(int, Throwable)})
     */
    @CircuitBreaker(name = "user-service", fallbackMethod = "fetchUsersPageFallback")
    @Retry(name = "user-service")
    public List<Map<String, Object>> fetchUsersPage(int size) {
        return fetchPageContent(userServiceWebClient,
                "/api/v1/users?page=0&size=" + size, "users");
    }

    /**
     * Resilience4j fallback for {@link #fetchUsersPage(int)} — logs and returns
     * an empty list so the dashboard's user counts read zero rather than failing.
     *
     * @param size requested page size (echoed for log correlation)
     * @param ex   the failure / CB-open cause
     * @return an empty list
     */
    @SuppressWarnings("unused")
    private List<Map<String, Object>> fetchUsersPageFallback(int size, Throwable ex) {
        log.warn("user-service /users CB OPEN or call failed: {}. Returning empty.", ex.toString());
        return Collections.emptyList();
    }

    // ── token-service ─────────────────────────────────────────────

    /**
     * GET /api/v1/tokens/{id} — single token detail document, used by token
     * analytics (supply / holders / transfer volume).
     *
     * @param tokenId id of the token to fetch
     * @return the token-detail map, or an empty map if the body is {@code null}
     *         or the call fails / CB is open (via
     *         {@link #fetchTokenDetailFallback(UUID, Throwable)})
     */
    @CircuitBreaker(name = "token-service", fallbackMethod = "fetchTokenDetailFallback")
    @Retry(name = "token-service")
    public Map<String, Object> fetchTokenDetail(UUID tokenId) {
        Map<String, Object> result = tokenServiceWebClient.get()
                .uri("/api/v1/tokens/{id}", tokenId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block(CALL_TIMEOUT);
        return result == null ? Collections.emptyMap() : result;
    }

    /**
     * Resilience4j fallback for {@link #fetchTokenDetail(UUID)} — logs and
     * returns an empty map so token analytics degrade to zeros.
     *
     * @param tokenId requested token id (echoed for log correlation)
     * @param ex      the failure / CB-open cause
     * @return an empty map
     */
    @SuppressWarnings("unused")
    private Map<String, Object> fetchTokenDetailFallback(UUID tokenId, Throwable ex) {
        log.warn("token-service /tokens/{} CB OPEN or call failed: {}. Returning empty.", tokenId, ex.toString());
        return Collections.emptyMap();
    }

    // ── fee-service ───────────────────────────────────────────────

    /**
     * GET /api/v1/fees/pool/{poolId}/history — daily fee history for a pool,
     * used by pool analytics' fee chart.
     *
     * @param poolId id of the pool whose fee history to fetch
     * @param days   number of trailing days to request
     * @return the list of fee-history entry maps, or empty if the body is
     *         {@code null} or the call fails / CB is open (via
     *         {@link #fetchPoolFeeHistoryFallback(UUID, int, Throwable)})
     */
    @CircuitBreaker(name = "fee-service", fallbackMethod = "fetchPoolFeeHistoryFallback")
    @Retry(name = "fee-service")
    public List<Map<String, Object>> fetchPoolFeeHistory(UUID poolId, int days) {
        List<Map<String, Object>> result = feeServiceWebClient.get()
                .uri("/api/v1/fees/pool/{poolId}/history?days={days}", poolId, days)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block(CALL_TIMEOUT);
        return result == null ? Collections.emptyList() : result;
    }

    /**
     * Resilience4j fallback for {@link #fetchPoolFeeHistory(UUID, int)} — logs
     * and returns an empty list so the fee chart renders empty.
     *
     * @param poolId requested pool id (echoed for log correlation)
     * @param days   requested day window (echoed for log correlation)
     * @param ex     the failure / CB-open cause
     * @return an empty list
     */
    @SuppressWarnings("unused")
    private List<Map<String, Object>> fetchPoolFeeHistoryFallback(UUID poolId, int days, Throwable ex) {
        log.warn("fee-service /fees/pool/{}/history CB OPEN or call failed: {}. Returning empty.",
                poolId, ex.toString());
        return Collections.emptyList();
    }

    // ── transaction-service ───────────────────────────────────────

    /**
     * GET the first page of transactions (used by the dashboard's
     * "transactions today" count). Page content is unwrapped via
     * {@link #fetchPageContent}.
     *
     * @param size page size to request (page index fixed at 0)
     * @return the page's transaction maps, or empty on error / CB-open (via
     *         {@link #fetchTransactionsPageFallback(int, Throwable)})
     */
    @CircuitBreaker(name = "transaction-service", fallbackMethod = "fetchTransactionsPageFallback")
    @Retry(name = "transaction-service")
    public List<Map<String, Object>> fetchTransactionsPage(int size) {
        return fetchPageContent(transactionServiceWebClient,
                "/api/v1/transactions?page=0&size=" + size, "transactions");
    }

    /**
     * Resilience4j fallback for {@link #fetchTransactionsPage(int)} — logs and
     * returns an empty list.
     *
     * @param size requested page size (echoed for log correlation)
     * @param ex   the failure / CB-open cause
     * @return an empty list
     */
    @SuppressWarnings("unused")
    private List<Map<String, Object>> fetchTransactionsPageFallback(int size, Throwable ex) {
        log.warn("transaction-service /transactions CB OPEN or call failed: {}. Returning empty.", ex.toString());
        return Collections.emptyList();
    }

    /**
     * GET a flat list of the most recent transactions (note: the {@code limit}
     * query returns a bare JSON array, not a {@code Page}, so this does not go
     * through {@link #fetchPageContent}). Feeds the suspicious-transaction scan.
     *
     * @param limit max number of recent transactions to request
     * @return the transaction maps, or empty if the body is {@code null} or the
     *         call fails / CB is open (via
     *         {@link #fetchRecentTransactionsFallback(int, Throwable)})
     */
    @CircuitBreaker(name = "transaction-service", fallbackMethod = "fetchRecentTransactionsFallback")
    @Retry(name = "transaction-service")
    public List<Map<String, Object>> fetchRecentTransactions(int limit) {
        List<Map<String, Object>> result = transactionServiceWebClient.get()
                .uri("/api/v1/transactions?limit=" + limit)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block(CALL_TIMEOUT);
        return result == null ? Collections.emptyList() : result;
    }

    /**
     * Resilience4j fallback for {@link #fetchRecentTransactions(int)} — logs and
     * returns an empty list (the suspicious-transaction scan then yields nothing).
     *
     * @param limit requested limit (echoed for log correlation)
     * @param ex    the failure / CB-open cause
     * @return an empty list
     */
    @SuppressWarnings("unused")
    private List<Map<String, Object>> fetchRecentTransactionsFallback(int limit, Throwable ex) {
        log.warn("transaction-service /transactions?limit={} CB OPEN or call failed: {}. Returning empty.",
                limit, ex.toString());
        return Collections.emptyList();
    }

    // ── price-oracle ──────────────────────────────────────────────

    /**
     * GET /api/v1/prices/{tokenId}/history — token price history, used by token
     * analytics' price chart.
     *
     * @param tokenId id of the token whose price history to fetch
     * @param days    number of trailing days to request
     * @return the list of price-history entry maps, or empty if the body is
     *         {@code null} or the call fails / CB is open (via
     *         {@link #fetchPriceHistoryFallback(UUID, int, Throwable)})
     */
    @CircuitBreaker(name = "price-oracle", fallbackMethod = "fetchPriceHistoryFallback")
    @Retry(name = "price-oracle")
    public List<Map<String, Object>> fetchPriceHistory(UUID tokenId, int days) {
        List<Map<String, Object>> result = priceOracleWebClient.get()
                .uri("/api/v1/prices/{tokenId}/history?days={days}", tokenId, days)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block(CALL_TIMEOUT);
        return result == null ? Collections.emptyList() : result;
    }

    /**
     * Resilience4j fallback for {@link #fetchPriceHistory(UUID, int)} — logs and
     * returns an empty list so the price chart renders empty.
     *
     * @param tokenId requested token id (echoed for log correlation)
     * @param days    requested day window (echoed for log correlation)
     * @param ex      the failure / CB-open cause
     * @return an empty list
     */
    @SuppressWarnings("unused")
    private List<Map<String, Object>> fetchPriceHistoryFallback(UUID tokenId, int days, Throwable ex) {
        log.warn("price-oracle /prices/{}/history CB OPEN or call failed: {}. Returning empty.",
                tokenId, ex.toString());
        return Collections.emptyList();
    }

    // ── shared helper for Page<...> shaped responses ──────────────

    /**
     * Shared GET-and-unwrap for Spring {@code Page<...>}-shaped JSON: fetches the
     * page as a {@code Map}, then returns its {@code content} array.
     *
     * <p>Crucially, HTTP-level errors (4xx/5xx) are swallowed inline to
     * {@link Mono#empty()} so the surrounding {@link Retry} sees a "success" and
     * the breaker is <em>not</em> tripped — only genuine network / timeout
     * failures count against the CB and reach a caller's fallback method.
     *
     * @param client the per-downstream {@link WebClient} to call
     * @param uri    the request URI (path + query) relative to the client's base URL
     * @param label  short downstream label for the warn log on HTTP error
     * @return the {@code content} list, or an empty list on error, null body, or
     *         an unexpected (non-list) {@code content} shape
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchPageContent(WebClient client, String uri, String label) {
        Map<String, Object> page = client.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorResume(ex -> {
                    // HTTP-level errors (404 from a path typo, 500 from a
                    // bad query) shouldn't trip the CB — only network /
                    // timeout failures should. Surface as empty page so
                    // the Retry advice sees a "success" and stops.
                    log.warn("Downstream {} returned error: {}", label, ex.toString());
                    return Mono.empty();
                })
                .block(CALL_TIMEOUT);
        if (page == null) return Collections.emptyList();
        Object content = page.get("content");
        return content instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : Collections.emptyList();
    }
}
