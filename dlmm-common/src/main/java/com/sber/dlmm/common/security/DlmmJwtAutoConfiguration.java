package com.sber.dlmm.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-registers {@link JwtTokenProvider} and {@link JwtAuthenticationFilter}
 * for inbound JWT validation on servlet-stack services.
 *
 * Conditions: both spring-webmvc ({@code OncePerRequestFilter}) AND jjwt
 * ({@code io.jsonwebtoken.Jwts}) must be present. Services without jjwt
 * on the classpath (e.g. price-oracle, which is a read-only stub today)
 * silently skip this auto-config instead of crashing with
 * ClassNotFoundException at startup.
 *
 * <p>The Redis-backed {@link JwtRevocationService} bean lives in a
 * separate {@link DlmmJwtRedisRevocationAutoConfiguration} class — its
 * own @ConditionalOnClass gates the entire class so services without
 * spring-data-redis on the classpath don't try to introspect a method
 * signature referencing {@code StringRedisTemplate}. The Noop fallback
 * stays here so it always registers (any service without the Redis
 * bean gets Noop instead of a startup crash).
 *
 * <p>The Bearer-forwarding {@code WebClientCustomizer} lives in
 * {@link DlmmWebClientAutoConfiguration} for the same isolation reason.
 */
@AutoConfiguration
@ConditionalOnClass(name = {
        "org.springframework.web.filter.OncePerRequestFilter",
        "io.jsonwebtoken.Jwts"
})
public class DlmmJwtAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtTokenProvider dlmmJwtTokenProvider(@Value("${dlmm.jwt.secret}") String secret) {
        return new JwtTokenProvider(secret);
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtAuthenticationFilter dlmmJwtAuthenticationFilter(JwtTokenProvider provider,
                                                                JwtRevocationService revocationService) {
        return new JwtAuthenticationFilter(provider, revocationService);
    }

    /**
     * Fallback {@link JwtRevocationService} for services without Redis.
     * Always registered if no other {@link JwtRevocationService} bean
     * already exists ({@link DlmmJwtRedisRevocationAutoConfiguration}
     * registers its Redis-backed variant first when Redis is on the
     * classpath, so this one is the actual fallback).
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtRevocationService dlmmNoopJwtRevocationService() {
        return new NoopJwtRevocationService();
    }
}
