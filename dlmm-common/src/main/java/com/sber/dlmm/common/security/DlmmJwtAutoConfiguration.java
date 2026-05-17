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
 * The Bearer-forwarding {@link org.springframework.boot.web.reactive.function.client.WebClientCustomizer}
 * lives in {@link DlmmWebClientAutoConfiguration} so that services without
 * spring-webflux on the classpath (e.g. user-service) don't fail bean
 * introspection.
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
    public JwtAuthenticationFilter dlmmJwtAuthenticationFilter(JwtTokenProvider provider) {
        return new JwtAuthenticationFilter(provider);
    }
}
