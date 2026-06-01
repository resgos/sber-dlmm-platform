package com.sber.dlmm.gateway.filter;

import com.sber.dlmm.common.enums.ApiTier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sprint 9 #6.6 — runtime per-tier rate limiting at gateway.
 *
 * <p>Runs after {@link JwtValidationFilter} (Ordered -100); reads the
 * {@code X-Api-Tier} header set there, picks the matching
 * {@link RedisRateLimiter} bean, and applies the rate limit using
 * {@link com.sber.dlmm.gateway.config.RateLimitConfig#tierKeyResolver()}.
 *
 * <p>Why not Spring Cloud Gateway's built-in {@code RequestRateLimiter}
 * filter? The built-in factory wires a single {@link RateLimiter} bean
 * per route/default-filter at config-time — it can't choose a different
 * bean per-request from a header. This custom filter is the clean
 * alternative without overriding the built-in factory.
 *
 * <p>On limit exceeded: returns 429 + {@code X-Tier-Limit} header so
 * clients (and Sprint 10 F-15 analytics) can attribute the throttle.
 *
 * <p>Skip list mirrors {@link JwtValidationFilter}'s — auth endpoints
 * and the public-data endpoints don't carry a tier and shouldn't be
 * rate-limited by this filter (they fall back to the IP-keyed limit
 * via the standard userKeyResolver in routes that opt in).
 */
@Component
public class TierBasedRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TierBasedRateLimitFilter.class);
    private static final List<String> SKIP_PATHS = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/actuator/**"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final KeyResolver tierKeyResolver;
    private final Map<ApiTier, RedisRateLimiter> limiters;

    /**
     * Sprint 9-DS-r4 (P1-14) — per-tier throttle counters surfaced to
     * Prometheus / Grafana. Two counters per tier:
     *   - dlmm_gateway_ratelimit_total{tier=FREE|PRO|ENTERPRISE,outcome=allowed}
     *   - dlmm_gateway_ratelimit_total{tier=FREE|PRO|ENTERPRISE,outcome=throttled}
     *
     * Allowed/throttled ratio per tier tells us at a glance whether a
     * tier is hitting its quota (and therefore whether to bump the
     * replenish rate, upsell to the next tier, or open an investigation
     * into a runaway client). Pre-registered into EnumMaps so the hot
     * path inside the reactive chain is just a {@code counter.increment()}
     * — no map.computeIfAbsent under load.
     */
    private final Map<ApiTier, Counter> allowedCounters;
    private final Map<ApiTier, Counter> throttledCounters;

    /**
     * NEW-2 (Sprint 14, Batch #3) — per-route throttle observability.
     *
     * <p>Same counter family (dlmm.gateway.ratelimit) but with an extra
     * {@code route} tag (gateway route id like "pool-engine",
     * "token-service"). Lazily registered per (tier, outcome, route)
     * triple — cardinality is bounded because routes are pre-defined in
     * application.yml (~15 entries) and tiers ∈ {FREE, PRO, ENTERPRISE},
     * so the worst case is 3 × 2 × 15 = 90 series — well inside
     * Prometheus's per-metric guardrail (default 1k).
     *
     * <p>Why a second counter family and not just adding `route` to the
     * existing one: backward compatibility. The Sprint 9 dashboard
     * (ApiAnalyticsPage) aggregates by (tier, outcome) only; adding a
     * tag would change the aggregation arithmetic via Prometheus's
     * {tier, outcome, route} expansion. Keeping the old family unchanged
     * means the existing UI keeps working while the new chart reads
     * the new family.
     */
    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> perRouteCounters = new ConcurrentHashMap<>();

    /**
     * Wires the tier→limiter lookup table and pre-registers the
     * tier×outcome Prometheus counters.
     *
     * <p>The three {@link RedisRateLimiter} beans are injected by qualifier
     * (they share a type, so {@code @Qualifier} disambiguates which bucket is
     * which) and folded into an immutable {@code Map<ApiTier, RedisRateLimiter>}
     * so {@link #filter} can pick a limiter by tier in O(1). The allowed/
     * throttled counters are eagerly built for every {@link ApiTier} here —
     * rather than lazily in the request path — so the reactive hot path is a
     * bare {@code counter.increment()} with no map insertion under load.
     *
     * @param tierKeyResolver       the {@code @Primary} tier-aware key
     *                              resolver from {@code RateLimitConfig}; yields
     *                              the {@code "{userId-or-ip}:{tier}"} Redis
     *                              bucket key
     * @param freeRateLimiter       FREE-tier limiter bean (10 rps / 15 burst)
     * @param proRateLimiter        PRO-tier limiter bean (100 rps / 150 burst)
     * @param enterpriseRateLimiter ENTERPRISE-tier limiter bean
     *                              (1000 rps / 1500 burst)
     * @param meterRegistry         Micrometer registry the throttle counters
     *                              (both the tier×outcome family here and the
     *                              lazy per-route family) register into
     */
    public TierBasedRateLimitFilter(@Qualifier("tierKeyResolver") KeyResolver tierKeyResolver,
                                     @Qualifier("freeRateLimiter") RedisRateLimiter freeRateLimiter,
                                     @Qualifier("proRateLimiter") RedisRateLimiter proRateLimiter,
                                     @Qualifier("enterpriseRateLimiter") RedisRateLimiter enterpriseRateLimiter,
                                     MeterRegistry meterRegistry) {
        this.tierKeyResolver = tierKeyResolver;
        this.meterRegistry = meterRegistry;
        this.limiters = Map.of(
                ApiTier.FREE, freeRateLimiter,
                ApiTier.PRO, proRateLimiter,
                ApiTier.ENTERPRISE, enterpriseRateLimiter);

        this.allowedCounters = new EnumMap<>(ApiTier.class);
        this.throttledCounters = new EnumMap<>(ApiTier.class);
        for (ApiTier t : ApiTier.values()) {
            allowedCounters.put(t, Counter.builder("dlmm.gateway.ratelimit")
                    .description("Requests after per-tier rate-limit check")
                    .tag("tier", t.name())
                    .tag("outcome", "allowed")
                    .register(meterRegistry));
            throttledCounters.put(t, Counter.builder("dlmm.gateway.ratelimit")
                    .description("Requests after per-tier rate-limit check")
                    .tag("tier", t.name())
                    .tag("outcome", "throttled")
                    .register(meterRegistry));
        }
    }

    /**
     * NEW-2 — get or lazily create the per-route counter for this
     * (tier, outcome, route) triple. Key the cache by composite String
     * for ConcurrentHashMap.computeIfAbsent friendliness; the lookup
     * cost is one hash + equality, well inside reactive hot-path budget.
     */
    private Counter routeCounter(ApiTier tier, String outcome, String routeId) {
        String key = tier.name() + "|" + outcome + "|" + routeId;
        return perRouteCounters.computeIfAbsent(key, k ->
                Counter.builder("dlmm.gateway.ratelimit.route")
                        .description("Per-route requests after rate-limit check (Sprint 14 NEW-2)")
                        .tag("tier", tier.name())
                        .tag("outcome", outcome)
                        .tag("route", routeId)
                        .register(meterRegistry));
    }

    /**
     * Applies the per-tier Redis token-bucket limit to the current request.
     *
     * <p>Runs at order -50, i.e. <em>after</em> {@link JwtValidationFilter}
     * (-100) has validated the JWT and stamped the trusted
     * {@code X-Api-Tier} header — this filter trusts that header and never
     * re-parses the token. Flow:
     * <ol>
     *   <li>skip auth/actuator paths ({@link #shouldSkip}) and the public-data
     *       routes ({@code /api/v1/public/**}) — they carry no tier and are
     *       throttled (if at all) by the IP-keyed resolver on their own
     *       route, so they fall straight through here;</li>
     *   <li>map {@code X-Api-Tier} → {@link ApiTier} (defaulting via
     *       {@link ApiTier#fromClaim}) and look up that tier's limiter;</li>
     *   <li>resolve the bucket key, call {@link RedisRateLimiter#isAllowed},
     *       and on success forward the chain, incrementing the allowed
     *       counters; on failure short-circuit with {@code 429 TOO_MANY_
     *       REQUESTS} and increment the throttled counters.</li>
     * </ol>
     * Either way it copies the limiter's {@code X-RateLimit-*} headers onto the
     * response and adds {@code X-Tier-Limit} (tier + replenish rate) for client
     * and analytics attribution. The route id is resolved from the Spring
     * Cloud Gateway {@code GATEWAY_ROUTE_ATTR} purely for the per-route
     * observability tag (Sprint 14 NEW-2); the rate-limit bucket family itself
     * is still the single {@code "global"} key per (user, tier).
     *
     * @param exchange the current server exchange; its request supplies the
     *                 path and tier header, its response receives the rate-
     *                 limit headers (and the 429 status when throttled)
     * @param chain    the downstream gateway filter chain, invoked only when
     *                 the request is within its tier's quota
     * @return a {@link Mono} completing when the request has been forwarded
     *         (allowed) or the 429 response has been written (throttled)
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (shouldSkip(path)) {
            return chain.filter(exchange);
        }

        // Public-data endpoints: no JWT, no tier header — fall through.
        // The /api/v1/public/** route below uses the IP-keyed userKeyResolver
        // with FREE limits via its own default-filters override.
        if (path.startsWith("/api/v1/public/")) {
            return chain.filter(exchange);
        }

        String tierHeader = exchange.getRequest().getHeaders().getFirst(JwtValidationFilter.HEADER_API_TIER);
        ApiTier tier = ApiTier.fromClaim(tierHeader);
        RedisRateLimiter limiter = limiters.get(tier);

        // Route id used as the bucket family — single global bucket per
        // (user, tier) for now. Future: per-route family if we want
        // /pools to have its own quota separate from /swap.
        String routeId = "global";

        // NEW-2 (Sprint 14 Batch #3) — resolve route id for per-route
        // observability tagging. The GATEWAY_ROUTE_ATTR is set by
        // Spring Cloud Gateway's RoutePredicateHandlerMapping which
        // runs before global filters; we read it for the tag value.
        // Fallback to "unknown" so a route-less request (shouldn't
        // happen at this filter order) still gets recorded.
        Route gwRoute = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String observableRouteId = gwRoute != null ? gwRoute.getId() : "unknown";

        return tierKeyResolver.resolve(exchange)
                .flatMap(key -> limiter.isAllowed(routeId, key)
                        .flatMap(response -> {
                            // Spring Cloud Gateway's limiter writes
                            // X-RateLimit-* headers itself; we add X-Tier-Limit
                            // for attribution + a counter header for analytics.
                            exchange.getResponse().getHeaders().add(
                                    "X-Tier-Limit", tier.name() + ":" + tier.getReplenishRate() + "rps");
                            response.getHeaders().forEach((name, value) ->
                                    exchange.getResponse().getHeaders().add(name, value));
                            if (response.isAllowed()) {
                                // Sprint 9-DS-r4 (P1-14) — original tier×outcome counter.
                                allowedCounters.get(tier).increment();
                                // NEW-2 (Sprint 14) — per-route breakdown counter.
                                routeCounter(tier, "allowed", observableRouteId).increment();
                                return chain.filter(exchange);
                            }
                            throttledCounters.get(tier).increment();
                            routeCounter(tier, "throttled", observableRouteId).increment();
                            log.debug("Rate-limit exceeded: tier={} key={} route={}", tier, key, observableRouteId);
                            ServerHttpResponse resp = exchange.getResponse();
                            resp.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                            return resp.setComplete();
                        }));
    }

    /**
     * Tests whether the path is exempt from tier-based throttling.
     *
     * <p>Uses {@link AntPathMatcher} against {@link #SKIP_PATHS} (auth login/
     * register/refresh and {@code /actuator/**}). These endpoints either
     * precede login (so no tier exists yet) or are operational, and must stay
     * reachable; the list intentionally mirrors {@link JwtValidationFilter}'s
     * own skip list so the two filters agree on what "unauthenticated" means.
     *
     * @param path the request URI path
     * @return {@code true} if the path matches a skip pattern and should not be
     *         rate-limited by this filter
     */
    private boolean shouldSkip(String path) {
        return SKIP_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    /**
     * Orders this filter just after JWT validation.
     *
     * <p>{@code -50} is greater (later) than {@link JwtValidationFilter}'s
     * {@code -100} but still negative, so it runs <em>after</em> the trusted
     * {@code X-Api-Tier} header has been injected yet <em>before</em> the
     * request is proxied downstream — exactly where throttling must happen so
     * a rejected request never reaches an upstream service.
     *
     * @return {@code -50}
     */
    @Override
    public int getOrder() {
        // After JwtValidationFilter (-100), before downstream proxying.
        return -50;
    }
}
