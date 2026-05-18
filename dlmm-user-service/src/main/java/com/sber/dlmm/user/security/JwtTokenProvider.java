package com.sber.dlmm.user.security;

import com.sber.dlmm.common.enums.KycStatus;
import com.sber.dlmm.common.enums.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
@Slf4j
public class JwtTokenProvider {

    @Value("${dlmm.jwt.secret}")
    private String secret;

    @Value("${dlmm.jwt.access-token-expiry-minutes}")
    private long accessTokenExpiryMinutes;

    @Value("${dlmm.jwt.refresh-token-expiry-days}")
    private long refreshTokenExpiryDays;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(UUID userId, UserRole role, KycStatus kycStatus) {
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofMinutes(accessTokenExpiryMinutes));

        // Sprint 8 AU-3 — jti enables denylist on logout. Without it, revocation
        // would have no key to write against (Redis SET membership keyed by jti).
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("role", role.name())
                .claim("kycStatus", kycStatus.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public String generateRefreshToken(UUID userId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofDays(refreshTokenExpiryDays));

        // Sprint 8 AU-3 — refresh tokens also carry jti so logout can revoke
        // them (otherwise a logged-out user could refresh their way back in).
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("type", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public long getAccessTokenExpirySeconds() {
        return accessTokenExpiryMinutes * 60;
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID getUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public UserRole getRole(String token) {
        return UserRole.valueOf(parseClaims(token).get("role", String.class));
    }

    public KycStatus getKycStatus(String token) {
        return KycStatus.valueOf(parseClaims(token).get("kycStatus", String.class));
    }

    public boolean isRefreshToken(String token) {
        Claims claims = parseClaims(token);
        return "refresh".equals(claims.get("type", String.class));
    }
}
