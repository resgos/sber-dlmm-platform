package com.sber.dlmm.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Single source of truth for JWT validation/parsing across all downstream
 * services. Each service was previously shipping its own near-identical copy
 * (~7 services × ~60 lines).
 *
 * Bound from {@code dlmm.jwt.secret} via {@link DlmmJwtAutoConfiguration}.
 */
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final SecretKey secretKey;

    public JwtTokenProvider(String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception ex) {
            log.warn("Invalid JWT token: {}", ex.getMessage());
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

    /** Refresh tokens carry {@code "type":"refresh"} and must NOT authenticate API requests. */
    public boolean isRefreshToken(String token) {
        return "refresh".equals(parseClaims(token).get("type", String.class));
    }

    /** Subject as raw String (some services keep userId as String, e.g. admin-bff). */
    public String getUserIdAsString(String token) {
        return parseClaims(token).getSubject();
    }

    /** Subject as UUID (most services). */
    public UUID getUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public String getRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    /**
     * Returns the {@code roles} claim as a list, falling back to a single-element
     * list built from the {@code role} claim. Notification-service used the
     * plural form historically.
     */
    @SuppressWarnings("unchecked")
    public List<String> getRoles(String token) {
        Claims claims = parseClaims(token);
        Object roles = claims.get("roles");
        if (roles instanceof List<?> list && !list.isEmpty()) {
            return (List<String>) roles;
        }
        String single = claims.get("role", String.class);
        return single == null ? List.of() : List.of(single);
    }

    public String getKycStatus(String token) {
        return parseClaims(token).get("kycStatus", String.class);
    }

    /**
     * Returns the {@code jti} claim — the JWT ID. Used by Sprint 8 AU-3
     * revocation denylist. Returns null for legacy tokens issued before AU-3
     * (which didn't carry jti); callers must treat null as "not in denylist".
     */
    public String getJti(String token) {
        return parseClaims(token).getId();
    }

    /**
     * Returns the expiration in epoch seconds, for callers (logout endpoint)
     * that need to set a Redis TTL matching the remaining JWT lifetime.
     */
    public long getExpiryEpochSeconds(String token) {
        return parseClaims(token).getExpiration().toInstant().getEpochSecond();
    }
}
