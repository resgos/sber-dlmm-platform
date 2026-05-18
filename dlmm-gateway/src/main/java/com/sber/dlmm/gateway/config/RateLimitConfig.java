package com.sber.dlmm.gateway.config;

import com.sber.dlmm.common.enums.ApiTier;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Sprint 9 #6.6 — per-tier API rate limiting.
 *
 * <p>Pre-Sprint-9 this config had a single {@link RedisRateLimiter} (auto-
 * configured by Spring Cloud Gateway from the application.yml
 * {@code redis-rate-limiter.replenishRate} keys) and one {@link KeyResolver}
 * (userId or IP). Sprint 9 extends to:
 *
 * <ul>
 *   <li>Three {@link RedisRateLimiter} beans, one per {@link ApiTier} —
 *       see the enum for tuning (FREE 10/15, PRO 100/150, ENTERPRISE
 *       1000/1500). The Spring Cloud Gateway {@code RequestRateLimiter}
 *       filter picks one via SpEL in {@code application.yml}.</li>
 *   <li>{@link #tierKeyResolver()} — the user-id-or-IP partition key,
 *       *suffixed by tier*, so a user upgrading from FREE → PRO doesn't
 *       carry over their already-consumed FREE bucket (separate Redis
 *       keys: {@code request_rate_limiter.{userId}:{tier}}).</li>
 * </ul>
 *
 * <p>Routes config (in application.yml) uses SpEL to pick the right
 * limiter bean per request based on the {@code X-Api-Tier} header set
 * by {@link com.sber.dlmm.gateway.filter.JwtValidationFilter}.
 */
@Configuration
public class RateLimitConfig {

    /**
     * Legacy resolver — keys by userId or IP only. Retained for backward
     * compatibility with any route that references {@code @userKeyResolver}
     * directly. New routes should use {@link #tierKeyResolver()}.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just(userId);
            }
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            String hostAddress = remoteAddress != null
                    ? Objects.requireNonNull(remoteAddress.getAddress()).getHostAddress()
                    : "unknown";
            return Mono.just(hostAddress);
        };
    }

    /**
     * Sprint 9 #6.6 — tier-aware key resolver. Returns
     * {@code "{userId-or-ip}:{tier}"} so each tier has its own Redis
     * bucket. Marked {@link Primary} so Spring Cloud Gateway's auto-config
     * picks this one when a route doesn't specify {@code key-resolver}.
     */
    @Bean
    @Primary
    public KeyResolver tierKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            String tier = exchange.getRequest().getHeaders().getFirst("X-Api-Tier");
            if (tier == null || tier.isBlank()) tier = "FREE";

            String partition;
            if (userId != null && !userId.isBlank()) {
                partition = userId;
            } else {
                InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
                partition = remoteAddress != null
                        ? Objects.requireNonNull(remoteAddress.getAddress()).getHostAddress()
                        : "unknown";
            }
            return Mono.just(partition + ":" + tier);
        };
    }

    /** Free tier — 10 rps sustained, 15 burst. */
    @Bean("freeRateLimiter")
    public RedisRateLimiter freeRateLimiter() {
        return new RedisRateLimiter(
                ApiTier.FREE.getReplenishRate(),
                ApiTier.FREE.getBurstCapacity());
    }

    /** Pro tier — 100 rps, 150 burst. Small businesses + power users. */
    @Bean("proRateLimiter")
    public RedisRateLimiter proRateLimiter() {
        return new RedisRateLimiter(
                ApiTier.PRO.getReplenishRate(),
                ApiTier.PRO.getBurstCapacity());
    }

    /** Enterprise tier — 1000 rps, 1500 burst. Institutional + B2B issuers. */
    @Bean("enterpriseRateLimiter")
    public RedisRateLimiter enterpriseRateLimiter() {
        return new RedisRateLimiter(
                ApiTier.ENTERPRISE.getReplenishRate(),
                ApiTier.ENTERPRISE.getBurstCapacity());
    }
}
