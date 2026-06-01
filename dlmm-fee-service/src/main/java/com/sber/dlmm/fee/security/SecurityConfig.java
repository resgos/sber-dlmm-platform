package com.sber.dlmm.fee.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.sber.dlmm.common.security.JwtAuthenticationFilter;

/**
 * Spring Security setup for fee-service: stateless JWT auth on a resource server that
 * sits behind the gateway. Every request except actuator and the API-docs/Swagger UI
 * must carry a valid token; the shared {@link JwtAuthenticationFilter} (from
 * {@code dlmm-common}) validates it and builds the {@link org.springframework.security.core.Authentication}
 * the controllers read.
 *
 * <p>{@code @EnableMethodSecurity} turns on the {@code @PreAuthorize} checks used by the
 * admin-only fee endpoint. CSRF is disabled and sessions are stateless because auth is
 * carried entirely by the bearer token, not a server session/cookie.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** Shared JWT filter from {@code dlmm-common}; validates the bearer token and populates the security context. */
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Builds the stateless JWT filter chain: CSRF off, no HTTP session, actuator +
     * API-docs/Swagger permitted, everything else authenticated. The shared
     * {@link JwtAuthenticationFilter} runs before {@link UsernamePasswordAuthenticationFilter}
     * so the {@code Authentication} is in place by the time a controller is reached.
     *
     * <p>Actuator paths are matched via {@code EndpointRequest.toAnyEndpoint()} rather than
     * an ant pattern to dodge a Spring 6.2 {@code MvcRequestMatcher} inference quirk that
     * could otherwise silently leave those paths unmatched (and thus secured).
     *
     * @param http the {@link HttpSecurity} builder provided by Spring Security
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if the security configuration fails to build
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // EndpointRequest avoids the Spring 6.2 MvcRequestMatcher
                        // inference that silently drops actuator paths.
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
