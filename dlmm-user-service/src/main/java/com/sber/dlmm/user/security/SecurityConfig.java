package com.sber.dlmm.user.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.sber.dlmm.common.security.JwtAuthenticationFilter;

/**
 * Spring Security wiring for user-service's HTTP layer. Establishes a
 * stateless, JWT-authenticated filter chain and the password encoder used
 * for both user credentials and 2FA recovery codes.
 *
 * <p>Design choices:
 * <ul>
 *   <li><b>Stateless</b> — no HTTP session; every request is authenticated
 *       solely from its Bearer token. CSRF protection is therefore disabled
 *       (there is no session cookie to forge).</li>
 *   <li><b>JWT filter</b> — the shared {@link JwtAuthenticationFilter} from
 *       {@code dlmm-common} runs before the username/password filter,
 *       validating the token (signature, expiry, revocation denylist) and
 *       populating the {@code Authentication} that {@code @PreAuthorize}
 *       checks read.</li>
 *   <li><b>Public paths</b> — {@code /api/v1/auth/**} (login / register /
 *       refresh / logout), Swagger UI, and all actuator endpoints permit
 *       anonymous access; everything else requires authentication. Note this
 *       is the in-process chain; the gateway enforces a narrower public
 *       allow-list at the edge.</li>
 * </ul>
 *
 * <p>{@code @EnableMethodSecurity} activates the {@code @PreAuthorize}
 * role checks on the controller methods (ADMIN / SUPER_ADMIN gating).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * Builds the stateless security filter chain.
     *
     * <p>Disables CSRF (no session), forces {@code STATELESS} session
     * creation, declares the anonymous-permitted matchers (auth endpoints,
     * Swagger, actuator) and requires authentication for every other request,
     * then installs the JWT filter ahead of
     * {@link UsernamePasswordAuthenticationFilter} so the token-derived
     * {@code Authentication} is in place before authorization runs.
     *
     * @param http the Spring-provided {@link HttpSecurity} builder
     * @return the configured {@link SecurityFilterChain} bean
     * @throws Exception if the builder fails to assemble the chain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // EndpointRequest avoids the Spring 6.2 MvcRequestMatcher
                        // inference that silently drops actuator paths.
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * The application's password hasher — BCrypt at the library-default cost
     * factor. Used by {@code UserService} to hash and verify user passwords
     * and by {@code TwoFactorService} to hash 2FA recovery codes (a fast hash
     * would make their low-entropy codes brute-forceable). BCrypt is salted
     * and adaptive, so equal plaintexts yield different stored hashes.
     *
     * @return a {@link BCryptPasswordEncoder} bean shared across the service
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
