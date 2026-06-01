package com.sber.dlmm.transaction.security;

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
 * Spring Security setup for transaction-service.
 *
 * <p>Stateless, JWT-secured webmvc service: CSRF disabled (no cookies/sessions),
 * session policy STATELESS, and the shared {@link JwtAuthenticationFilter}
 * (auto-configured from dlmm-common) installed ahead of the username/password
 * filter to build the {@code Authentication} from the bearer token.
 *
 * <p>Authorization rules: Swagger UI / OpenAPI docs and all actuator endpoints
 * are public; every other request requires authentication. Fine-grained
 * role checks live on the controllers via {@code @PreAuthorize}, enabled here
 * by {@link EnableMethodSecurity}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Builds the service's single {@link SecurityFilterChain}: stateless,
     * CSRF-off, public Swagger + actuator, everything else authenticated, with
     * the JWT filter inserted before {@link UsernamePasswordAuthenticationFilter}.
     *
     * @param http the {@link HttpSecurity} builder provided by Spring
     * @return the configured filter chain
     * @throws Exception if the security configuration fails to build
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // EndpointRequest avoids the Spring 6.2 MvcRequestMatcher
                        // inference that silently drops actuator paths.
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
