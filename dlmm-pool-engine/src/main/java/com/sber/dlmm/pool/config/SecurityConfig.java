package com.sber.dlmm.pool.config;

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

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // EndpointRequest matches actuator endpoints via Spring Boot's
                        // EndpointRequestMatcher — bypasses the MvcRequestMatcher path
                        // inference that silently fails for non-MVC handlers, which
                        // would otherwise drop /actuator/** through to anyRequest().
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/pools", "/api/v1/pools/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/pools/swap/quote").permitAll()
                        // Sprint 11 G-22 — read-only preview, no balance
                        // mutation. Matches the /swap/quote permitAll
                        // policy. Gateway throttles unauthenticated POSTs
                        // by IP for abuse protection.
                        .requestMatchers(HttpMethod.POST, "/api/v1/pools/preview-add-liquidity").permitAll()
                        // Sprint 9 R-M-33 — Public Data API tiers. No auth;
                        // gateway rate-limits by IP per the public tier config.
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
