package com.sber.dlmm.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Global access-log filter and distributed-trace seeder for the gateway.
 *
 * <p>This is the outermost filter in the chain — {@link #getOrder()} returns
 * {@code -200}, the most negative order in the module, so it wraps
 * {@link JwtValidationFilter} (-100) and {@link TierBasedRateLimitFilter}
 * (-50) and therefore times the request end-to-end (including auth and
 * rate-limit work) and logs even requests those later filters reject.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><b>Correlation id.</b> Reads an inbound {@code X-Trace-Id}; if the
 *       client didn't supply one it mints a fresh UUID. The (possibly new)
 *       id is written back onto the mutated request so every downstream
 *       service and log line shares one correlation key for a request.</li>
 *   <li><b>Access logging.</b> Emits one line on entry (method, path, traceId)
 *       and one on completion (adds status + wall-clock duration), so latency
 *       and outcome are visible at the single public ingress.</li>
 * </ul>
 *
 * <p>This filter is observability-only: it never rejects a request and never
 * touches the identity/tier headers — that is {@link JwtValidationFilter}'s
 * job. It runs before auth deliberately so that unauthenticated and throttled
 * traffic is still logged.
 */
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    /** SLF4J logger; emits the per-request entry/completion access lines. */
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /**
     * Correlation-id header name. Honoured if the client (or an upstream edge
     * proxy) already set it, otherwise minted here; propagated downstream so
     * all services log under the same trace id.
     */
    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    /**
     * Logs the request, ensures a trace id exists, and times the exchange.
     *
     * <p>Why mutate the request: a trace id must exist for the whole call, so
     * if the client omitted {@code X-Trace-Id} we generate one and stamp it on
     * a mutated request (Spring's {@link ServerHttpRequest} is immutable, hence
     * {@code request.mutate()}). The completion log is deferred via
     * {@code .then(Mono.fromRunnable(...))} so it fires after the downstream
     * response is produced, capturing the real status code and total latency.
     *
     * @param exchange the current server exchange (request/response pair);
     *                 read for method/path/headers and mutated to carry the
     *                 trace id forward
     * @param chain    the remaining gateway filter chain to delegate to
     * @return a {@link Mono} that completes when the downstream chain completes
     *         and the completion line has been logged; never emits an error of
     *         its own (it only observes the chain)
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long startTime = System.currentTimeMillis();

        String traceId = request.getHeaders().getFirst(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        HttpMethod method = request.getMethod();
        String path = request.getURI().getPath();

        log.info("Incoming request: method={}, path={}, traceId={}", method, path, traceId);

        ServerHttpRequest mutatedRequest = request.mutate()
                .header(HEADER_TRACE_ID, traceId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        String finalTraceId = traceId;
        return chain.filter(mutatedExchange)
                .then(Mono.fromRunnable(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    int statusCode = mutatedExchange.getResponse().getStatusCode() != null
                            ? mutatedExchange.getResponse().getStatusCode().value()
                            : 0;
                    log.info("Completed request: method={}, path={}, traceId={}, status={}, duration={}ms",
                            method, path, finalTraceId, statusCode, duration);
                }));
    }

    /**
     * Places this filter first in the chain (lowest order runs outermost).
     *
     * <p>{@code -200} is more negative than {@link JwtValidationFilter} (-100)
     * and {@link TierBasedRateLimitFilter} (-50), so logging brackets all
     * other gateway work: the timing covers auth + rate-limit cost, and entry
     * lines are recorded even for requests that those filters later short-
     * circuit (401 / 429). The trace-id stamp also happens before auth so
     * rejected requests are still correlatable.
     *
     * @return {@code -200}, the gateway's outermost filter order
     */
    @Override
    public int getOrder() {
        return -200;
    }
}
