package com.sber.dlmm.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks down the parsing contract of the consolidated JwtTokenProvider so
 * future refactors of dlmm-common don't silently break auth across all
 * 7 downstream services.
 */
class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-for-jwt-token-provider-unit-test-32+bytes";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET);
    }

    @Test
    void validateToken_acceptsTokenSignedWithSameSecret() {
        String token = accessToken(UUID.randomUUID(), "USER", "VERIFIED");
        assertThat(provider.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_rejectsTokenSignedWithDifferentSecret() {
        SecretKey other = Keys.hmacShaKeyFor("a-completely-different-secret-key-32+bytes-please".getBytes());
        String tampered = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(other)
                .compact();
        assertThat(provider.validateToken(tampered)).isFalse();
    }

    @Test
    void validateToken_rejectsExpiredToken() {
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now().minus(Duration.ofHours(1))))
                .expiration(Date.from(Instant.now().minus(Duration.ofMinutes(30))))
                .signWith(KEY)
                .compact();
        assertThat(provider.validateToken(expired)).isFalse();
    }

    @Test
    void validateToken_rejectsMalformedToken() {
        assertThat(provider.validateToken("not.a.jwt")).isFalse();
        assertThat(provider.validateToken("")).isFalse();
        assertThat(provider.validateToken("rubbish")).isFalse();
    }

    @Test
    void getUserId_returnsUUIDFromSubject() {
        UUID userId = UUID.randomUUID();
        String token = accessToken(userId, "USER", "VERIFIED");
        assertThat(provider.getUserId(token)).isEqualTo(userId);
        assertThat(provider.getUserIdAsString(token)).isEqualTo(userId.toString());
    }

    @Test
    void getRole_returnsSingleRoleClaim() {
        String token = accessToken(UUID.randomUUID(), "ADMIN", "VERIFIED");
        assertThat(provider.getRole(token)).isEqualTo("ADMIN");
    }

    @Test
    void getKycStatus_returnsClaim() {
        String token = accessToken(UUID.randomUUID(), "USER", "PENDING");
        assertThat(provider.getKycStatus(token)).isEqualTo("PENDING");
    }

    @Test
    void getRoles_fallsBackToSingleRoleClaim() {
        String token = accessToken(UUID.randomUUID(), "USER", "VERIFIED");
        assertThat(provider.getRoles(token)).containsExactly("USER");
    }

    @Test
    void getRoles_returnsRolesArrayWhenPresent() {
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("roles", List.of("USER", "MARKET_MAKER"))
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .signWith(KEY)
                .compact();
        assertThat(provider.getRoles(token)).containsExactly("USER", "MARKET_MAKER");
    }

    @Test
    void isRefreshToken_trueForTypeRefresh() {
        String refresh = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("type", "refresh")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(Duration.ofDays(7))))
                .signWith(KEY)
                .compact();
        assertThat(provider.isRefreshToken(refresh)).isTrue();
    }

    @Test
    void isRefreshToken_falseForAccessToken() {
        String access = accessToken(UUID.randomUUID(), "USER", "VERIFIED");
        assertThat(provider.isRefreshToken(access)).isFalse();
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
}
