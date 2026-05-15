package com.sber.dlmm.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Shared servlet-stack JWT filter, replaces seven near-identical per-service
 * copies. On a valid access token, populates the {@link SecurityContextHolder}
 * with a {@link UsernamePasswordAuthenticationToken} where:
 * <ul>
 *   <li>principal = userId (String — preserves callers that read it as String;
 *       UUID-typed callers can use {@link JwtTokenProvider#getUserId(String)} directly)</li>
 *   <li>credentials = kycStatus (or null if absent)</li>
 *   <li>authorities = ROLE_* derived from the {@code role} or {@code roles} claim</li>
 * </ul>
 * Refresh tokens ({@code "type":"refresh"}) are rejected — they must only be
 * usable against {@code /auth/refresh}, not against business endpoints.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                if (jwtTokenProvider.validateToken(token) && !jwtTokenProvider.isRefreshToken(token)) {
                    String userId = jwtTokenProvider.getUserIdAsString(token);
                    String kycStatus = jwtTokenProvider.getKycStatus(token);
                    List<String> roles = jwtTokenProvider.getRoles(token);

                    List<SimpleGrantedAuthority> authorities = roles.stream()
                            .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                            .toList();

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userId, kycStatus, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    if (log.isDebugEnabled()) {
                        log.debug("JWT authenticated user={} roles={}", userId, roles);
                    }
                }
            } catch (Exception ex) {
                log.debug("JWT authentication skipped: {}", ex.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
