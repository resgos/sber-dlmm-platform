package com.sber.dlmm.gateway.filter;

import com.sber.dlmm.common.enums.ApiTier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

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

    public TierBasedRateLimitFilter(@Qualifier("tierKeyResolver") KeyResolver tierKeyResolver,
                                     @Qualifier("freeRateLimiter") RedisRateLimiter freeRateLimiter,
                                     @Qualifier("proRateLimiter") RedisRateLimiter proRateLimiter,
                                     @Qualifier("enterpriseRateLimiter") RedisRateLimiter enterpriseRateLimiter) {
        this.tierKeyResolver = tierKeyResolver;
        this.limiters = Map.of(
                ApiTier.FREE, freeRateLimiter,
                ApiTier.PRO, proRateLimiter,
                ApiTier.ENTERPRISE, enterpriseRateLimiter);
    }

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
                                return chain.filter(exchange);
                            }
                            log.debug("Rate-limit exceeded: tier={} key={}", tier, key);
                            ServerHttpResponse resp = exchange.getResponse();
                            resp.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                            return resp.setComplete();
                        }));
    }

    private boolean shouldSkip(String path) {
        return SKIP_PATHS.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    public int getOrder() {
        // After JwtValidationFilter (-100), before downstream proxying.
        return -50;
    }
}
