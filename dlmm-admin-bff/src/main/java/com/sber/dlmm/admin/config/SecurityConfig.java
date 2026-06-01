package com.sber.dlmm.admin.config;

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
 * HTTP security for the admin BFF. The gateway already validates JWTs at the
 * edge, but every downstream (this one included) re-validates independently via
 * the shared {@link JwtAuthenticationFilter} so it can never be reached behind
 * the gateway's back. The chain is stateless (no session), CSRF-disabled (token
 * auth, no cookies), and locks {@code /api/v1/admin/**} to the ADMIN /
 * SUPER_ADMIN roles while leaving actuator and Swagger open for ops tooling.
 *
 * <p>{@code @EnableMethodSecurity} also turns on {@code @PreAuthorize} so
 * controllers can layer finer-grained checks on top of the URL rules below.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * @param jwtAuthenticationFilter the shared filter (from dlmm-common,
     *                                auto-configured) that parses the inbound
     *                                bearer token and populates the Spring
     *                                {@code SecurityContext}; inserted into the
     *                                chain by {@link #securityFilterChain}
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * Defines the single security filter chain for the service.
     *
     * <p>Rules, in order: actuator endpoints and Swagger/OpenAPI are public (ops
     * + docs); {@code /api/v1/admin/**} requires role ADMIN or SUPER_ADMIN;
     * everything else just needs an authenticated principal. The JWT filter runs
     * before {@link UsernamePasswordAuthenticationFilter} so the context is
     * populated before authorization is evaluated.
     *
     * @param http the Spring Security builder for the servlet chain
     * @return the built {@link SecurityFilterChain}
     * @throws Exception if {@link HttpSecurity#build()} fails to assemble the chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Match actuator via EndpointRequest — string matchers route through
                // MvcRequestMatcher in Spring 6.2 and silently skip non-MVC handlers.
                .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
