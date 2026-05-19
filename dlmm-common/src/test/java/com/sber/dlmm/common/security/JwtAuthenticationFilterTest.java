package com.sber.dlmm.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the contract of the consolidated servlet JWT filter:
 * - access tokens populate SecurityContext (principal=userId String,
 *   credentials=kycStatus, authorities=ROLE_*)
 * - refresh tokens are silently dropped (no auth, no error) so they
 *   cannot grant API access via the Bearer header
 * - missing/invalid tokens leave SecurityContext untouched
 * - the filter never throws — chain.doFilter is always called
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "filter-test-secret-key-32-bytes-minimum-for-hs256+";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private JwtAuthenticationFilter filter;
    private FilterChain chain;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(new JwtTokenProvider(SECRET));
        chain = mock(FilterChain.class);
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void accessToken_populatesSecurityContextAndContinuesChain() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = accessToken(userId, "USER", "VERIFIED");
        MockHttpServletRequest request = bearer(token);

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(userId);
        assertThat(auth.getCredentials()).isEqualTo("VERIFIED");
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
        verify(chain).doFilter(request, response);
    }

    @Test
    void refreshToken_isRejectedAsAuth_butChainContinues() throws Exception {
        String refresh = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("type", "refresh")
                .claim("role", "USER")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(Duration.ofDays(7))))
                .signWith(KEY)
                .compact();
        MockHttpServletRequest request = bearer(refresh);

        filter.doFilter(request, response, chain);

        // Filter must not authenticate the request — refresh tokens are only
        // valid against /auth/refresh, not against business endpoints.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void noBearer_leavesContextEmpty_butChainContinues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void invalidToken_leavesContextEmpty_butChainContinues() throws Exception {
        MockHttpServletRequest request = bearer("not-a-jwt-at-all");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void revokedJti_isRejected_butChainContinues() throws Exception {
        // Sprint 8 AU-3 — revoked tokens must not authenticate.
        UUID userId = UUID.randomUUID();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .id(jti)
                .subject(userId.toString())
                .claim("role", "USER")
                .claim("kycStatus", "VERIFIED")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(30))))
                .signWith(KEY)
                .compact();

        JwtRevocationService revocation = mock(JwtRevocationService.class);
        when(revocation.isRevoked(jti)).thenReturn(true);
        JwtAuthenticationFilter revocationAwareFilter =
                new JwtAuthenticationFilter(new JwtTokenProvider(SECRET), revocation);

        MockHttpServletRequest request = bearer(token);
        revocationAwareFilter.doFilter(request, response, chain);

        // Auth must NOT be populated — token was on the denylist.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verify(revocation).isRevoked(jti);
    }

    @Test
    void nonRevokedJti_isAccepted() throws Exception {
        // Counterpoint to the above — same setup but isRevoked returns false.
        UUID userId = UUID.randomUUID();
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .id(jti)
                .subject(userId.toString())
                .claim("role", "USER")
                .claim("kycStatus", "VERIFIED")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(30))))
                .signWith(KEY)
                .compact();

        JwtRevocationService revocation = mock(JwtRevocationService.class);
        when(revocation.isRevoked(jti)).thenReturn(false);
        JwtAuthenticationFilter revocationAwareFilter =
                new JwtAuthenticationFilter(new JwtTokenProvider(SECRET), revocation);

        MockHttpServletRequest request = bearer(token);
        revocationAwareFilter.doFilter(request, response, chain);

        // Auth populated — revocation service was consulted and returned false.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo(userId);
        verify(revocation).isRevoked(jti);
    }

    @Test
    void legacyTokenWithoutJti_isAccepted_andRevocationCheckedWithNull() throws Exception {
        // Tokens minted before Sprint 8 AU-3 don't carry jti. They must keep
        // working until expiry — NoopJwtRevocationService.isRevoked(null) is false.
        UUID userId = UUID.randomUUID();
        // Note: NO .id(...) — legacy shape.
        String token = accessToken(userId, "USER", "VERIFIED");

        MockHttpServletRequest request = bearer(token);
        filter.doFilter(request, response, chain);  // default ctor → Noop revocation

        // Legacy token still authenticates.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo(userId);
    }

    @Test
    void rolesArrayClaim_producesMultipleAuthorities() throws Exception {
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", java.util.List.of("USER", "MARKET_MAKER"))
                .claim("kycStatus", "VERIFIED")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(30))))
                .signWith(KEY)
                .compact();
        MockHttpServletRequest request = bearer(token);

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER", "ROLE_MARKET_MAKER");
    }

    private String accessToken(UUID userId, String role, String kyc) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .claim("kycStatus", kyc)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(30))))
                .signWith(KEY)
                .compact();
    }

    private static MockHttpServletRequest bearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
