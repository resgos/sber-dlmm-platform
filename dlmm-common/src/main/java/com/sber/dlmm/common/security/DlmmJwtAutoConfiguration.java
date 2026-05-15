package com.sber.dlmm.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * Auto-registers JWT support across the platform:
 * <ul>
 *   <li>{@link JwtTokenProvider} + {@link JwtAuthenticationFilter} for inbound
 *       JWT validation on servlet-stack services (gateway is reactive and has
 *       its own {@code JwtValidationFilter} — guarded by {@code @ConditionalOnClass}
 *       on {@code OncePerRequestFilter}).</li>
 *   <li>{@link WebClientCustomizer} that installs
 *       {@link BearerTokenForwardingFilter} on every {@code WebClient.Builder}
 *       in the context — so service-to-service WebClient calls automatically
 *       carry the inbound caller's Bearer token. Without this, downstream
 *       services protected by JWT reject the call with 403.</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.web.filter.OncePerRequestFilter")
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

    /**
     * Applied to every {@code WebClient.Builder} bean — propagates inbound
     * {@code Authorization} header onto outgoing WebClient calls when present.
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
    public WebClientCustomizer dlmmBearerForwardingCustomizer() {
        return builder -> builder.filter(BearerTokenForwardingFilter.create());
    }
}
