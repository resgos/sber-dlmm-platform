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

    /**
     * Allowed browser origins, bound from the comma-separated
     * {@code dlmm.cors.allowed-origins} property and split with SpEL into a
     * list. Profile-specific (localhost UIs in dev, Sber-owned domains in
     * prod) and env-var overridable — see the class Javadoc for why the
     * comma-string + {@code .split(',')} form is used instead of a YAML list.
     */
    @Value("#{'${dlmm.cors.allowed-origins}'.split(',')}")
    private List<String> allowedOrigins;

    /**
     * Builds the single reactive CORS filter applied to every gateway route.
     *
     * <p>Why this bean exists: as the sole public ingress the gateway, not the
     * downstream services, owns CORS for browser SPAs. The policy here:
     * <ul>
     *   <li>origins restricted to {@link #allowedOrigins} (never {@code *}),
     *       because {@code allowCredentials(true)} below forbids a wildcard
     *       origin and would also leak cookies/Authorization cross-site;</li>
     *   <li>methods limited to the verbs the API actually uses (GET/POST/
     *       PUT/DELETE) plus OPTIONS so the browser pre-flight succeeds;</li>
     *   <li>request headers limited to {@code Authorization} (the bearer JWT),
     *       {@code Content-Type}, and {@code X-Trace-Id} (the correlation id
     *       from {@link com.sber.dlmm.gateway.filter.RequestLoggingFilter});</li>
     *   <li>credentials allowed so the SPAs may send the bearer token;</li>
     *   <li>pre-flight cached for 3600s to cut OPTIONS chatter.</li>
     * </ul>
     * The config is registered for {@code /**} so it covers the whole route
     * table from one place.
     *
     * @return a {@link CorsWebFilter} enforcing the above policy on all paths
     */
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
