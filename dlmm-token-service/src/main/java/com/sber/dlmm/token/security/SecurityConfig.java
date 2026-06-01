package com.sber.dlmm.token.security;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.sber.dlmm.common.security.JwtAuthenticationFilter;

/**
 * HTTP security wiring for token-service.
 *
 * <p>The service sits behind the gateway, which already validates the JWT and
 * injects {@code X-User-*} headers. This config still re-establishes a Spring
 * {@link org.springframework.security.core.Authentication} per request by
 * placing the shared {@link JwtAuthenticationFilter} (auto-configured from
 * dlmm-common) ahead of the username/password filter, so {@code @PreAuthorize}
 * and the URL rules below have a populated security context to evaluate.
 *
 * <p><b>Authorization model:</b> stateless (no HTTP session), CSRF off (token
 * auth, not cookies). Swagger and all actuator endpoints are public; the
 * admin-only token-lifecycle mutations (create/mint/burn/pause/unpause) and
 * arbitrary balance lookups require {@code ADMIN}/{@code SUPER_ADMIN}; every
 * other request must be authenticated. Method-level rules are also enabled via
 * {@link EnableMethodSecurity} for finer-grained {@code @PreAuthorize} checks
 * on controllers.
 *
 * <p>Collaborators: {@link JwtAuthenticationFilter} (token → Authentication).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * @param jwtAuthenticationFilter shared filter (from dlmm-common) that turns a
     *        validated Bearer token into a Spring {@code Authentication}; installed
     *        into the chain by {@link #securityFilterChain(HttpSecurity)}
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * Builds the single {@link SecurityFilterChain} for the service: stateless
     * sessions, CSRF disabled, the public allow-list + admin-role rules described
     * on the class, and the JWT filter wired in before
     * {@link UsernamePasswordAuthenticationFilter}.
     *
     * @param http Spring's mutable security builder for this chain
     * @return the assembled filter chain bean
     * @throws Exception if Spring Security fails to assemble the chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        // EndpointRequest avoids the Spring 6.2 MvcRequestMatcher
                        // inference that silently drops actuator paths.
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/tokens").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/tokens/mint").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/tokens/burn").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/tokens/*/pause").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/tokens/*/unpause").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/balances/user/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
