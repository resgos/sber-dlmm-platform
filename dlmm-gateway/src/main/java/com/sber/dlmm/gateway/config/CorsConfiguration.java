package com.sber.dlmm.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS for the gateway. Allowed origins come from
 * {@code dlmm.cors.allowed-origins} (comma-separated) so the dev profile
 * (localhost UIs) and the prod profile (locked to Sber-owned domains)
 * can use the same filter without recompiling. See application.yml /
 * application-prod.yml for the actual values.
 *
 * Comma-separated string with SpEL split (rather than a YAML list) is the
 * pattern that binds cleanly across @Value, env-var overrides, and the
 * Spring Cloud Gateway bootstrap order — YAML lists were silently
 * unresolved by the @Value placeholder resolver in this stack.
 *
 * Closes risk register #9 — dev CORS isn't shipped to prod.
 */
@Configuration
public class CorsConfiguration {

    @Value("#{'${dlmm.cors.allowed-origins}'.split(',')}")
    private List<String> allowedOrigins;

    @Bean
    public CorsWebFilter corsWebFilter() {
        org.springframework.web.cors.CorsConfiguration corsConfig = new org.springframework.web.cors.CorsConfiguration();
        corsConfig.setAllowedOrigins(allowedOrigins);
        corsConfig.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()
        ));
        corsConfig.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Trace-Id"));
        corsConfig.setAllowCredentials(true);
        corsConfig.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);

        return new CorsWebFilter(source);
    }
}
