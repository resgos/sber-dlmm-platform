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
 *   <li>principal = userId as {@link java.util.UUID} — every JWT subject in
 *       this platform is a UUID (users.id column type). Most controllers
 *       cast to UUID directly; storing as String would break them with
 *       ClassCastException at runtime. String-typed callers can call
 *       {@code auth.getPrincipal().toString()}.</li>
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
    private final JwtRevocationService revocationService;

    /**
     * Test-friendly ctor that wires a {@link NoopJwtRevocationService} — only
     * for callers that don't care about Sprint 8 AU-3 (i.e. legacy tests).
     * Production wiring goes via {@link DlmmJwtAutoConfiguration} which
     * picks the Redis-backed impl when available.
     *
     * @param jwtTokenProvider validates signatures and reads claims; revocation
     *                        checks are disabled (no-op denylist)
     */
    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this(jwtTokenProvider, new NoopJwtRevocationService());
    }

    /**
     * Production constructor — wires both the token parser and the revocation
     * denylist. Invoked by {@link DlmmJwtAutoConfiguration}, which supplies the
     * Redis-backed {@link JwtRevocationService} where available and the no-op
     * fallback otherwise.
     *
     * @param jwtTokenProvider validates signatures and reads claims
     * @param revocationService consulted per request to reject revoked {@code jti}s
     */
    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                    JwtRevocationService revocationService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.revocationService = revocationService;
    }

    /**
     * Authenticates the request from a {@code Bearer} access token, if present.
     *
     * <p>This filter is <em>permissive</em>: a missing, malformed, expired,
     * revoked or refresh-type token leaves the {@link SecurityContextHolder}
     * unauthenticated and the chain continues — it never short-circuits with a
     * 401 itself. Authorization is enforced downstream (Spring Security's
     * {@code authorizeHttpRequests} / {@code @PreAuthorize}), which rejects the
     * now-anonymous request. This keeps the filter reusable across services with
     * different public-endpoint sets.
     *
     * <p>Order of checks (fail-closed at each step):
     * <ol>
     *   <li>signature/expiry valid ({@link JwtTokenProvider#validateToken});</li>
     *   <li>not a refresh token (refresh tokens are barred from business
     *       endpoints — see {@link JwtTokenProvider#isRefreshToken});</li>
     *   <li>{@code jti} not on the revocation denylist
     *       ({@link JwtRevocationService#isRevoked}); a revoked token leaves the
     *       context anonymous.</li>
     * </ol>
     * On success it sets a {@link UsernamePasswordAuthenticationToken} whose
     * principal is the userId {@link java.util.UUID} (falling back to the raw
     * String only if the subject isn't a UUID), credentials are the kycStatus,
     * and authorities are {@code ROLE_*}. Any unexpected exception is caught and
     * the request proceeds unauthenticated.
     *
     * @param request the inbound HTTP request, read for the {@code Authorization} header
     * @param response the HTTP response (passed through untouched)
     * @param filterChain the remaining filter chain, always invoked once
     * @throws ServletException if a downstream filter raises it
     * @throws IOException if a downstream filter raises it
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                if (jwtTokenProvider.validateToken(token) && !jwtTokenProvider.isRefreshToken(token)) {
                    // Sprint 8 AU-3 — denylist check. jti may be null for legacy
                    // tokens issued before this commit; isRevoked handles that.
                    String jti = jwtTokenProvider.getJti(token);
                    if (revocationService.isRevoked(jti)) {
                        log.debug("Rejecting revoked JWT jti={}", jti);
                        filterChain.doFilter(request, response);
                        return;
                    }
                    String userIdRaw = jwtTokenProvider.getUserIdAsString(token);
                    // Sprint 9 fix — principal is UUID, not String. Most
                    // controllers do `(UUID) auth.getPrincipal()` and were
                    // crashing with ClassCastException after the Sprint 6
                    // dedup consolidation. UUID parse falls back to the
                    // raw String when subject isn't a UUID (defensive).
                    Object principal;
                    try {
                        principal = java.util.UUID.fromString(userIdRaw);
                    } catch (IllegalArgumentException ex) {
                        principal = userIdRaw;
                    }
                    String kycStatus = jwtTokenProvider.getKycStatus(token);
                    List<String> roles = jwtTokenProvider.getRoles(token);

                    List<SimpleGrantedAuthority> authorities = roles.stream()
                            .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                            .toList();

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, kycStatus, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    if (log.isDebugEnabled()) {
                        log.debug("JWT authenticated user={} roles={}", userIdRaw, roles);
                    }
                }
            } catch (Exception ex) {
                log.debug("JWT authentication skipped: {}", ex.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
