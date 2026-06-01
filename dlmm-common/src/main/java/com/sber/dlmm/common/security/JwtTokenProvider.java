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
 * <p>This is the <em>validation</em> side of the platform's JWT model: it only
 * verifies signatures and reads claims. Tokens are <em>issued</em> elsewhere
 * (user-service's own {@code JwtTokenProvider}). Access tokens are signed with
 * HMAC-SHA384 (HS384); the same shared {@code dlmm.jwt.secret} is used to sign
 * and to verify, so every service can validate a token without a round-trip to
 * the issuer. Claims this class reads: {@code sub} (userId UUID),
 * {@code role}/{@code roles}, {@code kycStatus}, {@code tier} (FREE/PRO/
 * ENTERPRISE — API-tier rate limiting), {@code jti} (revocation denylist key),
 * and {@code type} ({@code "refresh"} marks a refresh token, which must never
 * authenticate a business request).
 *
 * <p>Stateless and thread-safe: the derived {@link SecretKey} is immutable and
 * jjwt parsers are built per call. A single instance is shared application-wide.
 *
 * <p>Bound from {@code dlmm.jwt.secret} via {@link DlmmJwtAutoConfiguration}.
 */
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final SecretKey secretKey;

    /**
     * Derives the HMAC {@link SecretKey} once from the shared signing secret.
     *
     * <p>The same secret signs (in user-service) and verifies (here), so the
     * value MUST match across every service or tokens fail to validate. The
     * secret length is enforced at startup by {@code SecretValidationOnStartup}
     * (HS* requires ≥ 32 bytes); {@code Keys.hmacShaKeyFor} would otherwise
     * throw a {@code WeakKeyException} on a short key.
     *
     * @param secret the raw shared signing secret from {@code dlmm.jwt.secret};
     *               must be ≥ 32 bytes (UTF-8) or key derivation fails
     */
    public JwtTokenProvider(String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Verifies a token's signature, structure and expiry without throwing.
     *
     * <p>Fail-closed: any parse/verify problem (bad signature, malformed token,
     * expired, wrong algorithm) is caught and reported as invalid rather than
     * propagated, so callers in the filter chain can simply skip authentication.
     * The reason is logged at WARN for diagnostics.
     *
     * @param token the compact JWS string (without the {@code "Bearer "} prefix)
     * @return {@code true} if the token is well-formed, correctly signed and not
     *         expired; {@code false} otherwise
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception ex) {
            log.warn("Invalid JWT token: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * Verifies the signature and returns the parsed claim set. Unlike
     * {@link #validateToken(String)} this propagates failures, so it is the
     * right entry point when the caller wants the claims (and is prepared to
     * handle an invalid token via exception).
     *
     * @param token the compact JWS string (without the {@code "Bearer "} prefix)
     * @return the verified token payload (claims)
     * @throws io.jsonwebtoken.JwtException if the token is malformed, has a bad
     *         signature, is expired, or otherwise fails verification
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Refresh tokens carry {@code "type":"refresh"} and must NOT authenticate API requests.
     *
     * <p>The inbound JWT filter calls this so a long-lived refresh token cannot
     * be replayed against business endpoints — it is only valid at
     * {@code /auth/refresh}. Access tokens omit the {@code type} claim, so this
     * returns {@code false} for them.
     *
     * @param token a verified token (call after {@link #validateToken(String)})
     * @return {@code true} if this is a refresh token; {@code false} for access tokens
     * @throws io.jsonwebtoken.JwtException if the token fails verification
     */
    public boolean isRefreshToken(String token) {
        return "refresh".equals(parseClaims(token).get("type", String.class));
    }

    /**
     * Subject as raw String (some services keep userId as String, e.g. admin-bff).
     *
     * @param token a verified token
     * @return the {@code sub} claim verbatim (the userId)
     * @throws io.jsonwebtoken.JwtException if the token fails verification
     */
    public String getUserIdAsString(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Subject as UUID (most services).
     *
     * @param token a verified token
     * @return the {@code sub} claim parsed as a {@link UUID}
     * @throws io.jsonwebtoken.JwtException if the token fails verification
     * @throws IllegalArgumentException if the subject is not a valid UUID
     */
    public UUID getUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    /**
     * Returns the singular {@code role} claim (e.g. {@code "USER"},
     * {@code "ADMIN"}, {@code "SUPER_ADMIN"}). Prefer {@link #getRoles(String)}
     * when a service may issue the plural {@code roles} claim.
     *
     * @param token a verified token
     * @return the {@code role} claim, or {@code null} if absent
     * @throws io.jsonwebtoken.JwtException if the token fails verification
     */
    public String getRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    /**
     * Returns the {@code roles} claim as a list, falling back to a single-element
     * list built from the {@code role} claim. Notification-service used the
     * plural form historically.
     *
     * @param token a verified token
     * @return the roles as a non-null list; empty when neither {@code roles}
     *         nor {@code role} is present
     * @throws io.jsonwebtoken.JwtException if the token fails verification
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

    /**
     * Returns the {@code kycStatus} claim. The inbound filter stores this as the
     * authentication's credentials so controllers can gate KYC-restricted
     * actions without a round-trip to user-service.
     *
     * @param token a verified token
     * @return the {@code kycStatus} claim (e.g. {@code "VERIFIED"}), or
     *         {@code null} if absent
     * @throws io.jsonwebtoken.JwtException if the token fails verification
     */
    public String getKycStatus(String token) {
        return parseClaims(token).get("kycStatus", String.class);
    }

    /**
     * Sprint 9 #6.6 — API tier claim. Returns the raw string ("FREE" / "PRO"
     * / "ENTERPRISE") or null if absent. Callers parse via
     * {@code ApiTier.fromClaim(...)} which defaults to FREE on null.
     *
     * @param token a verified token
     * @return the {@code tier} claim, or {@code null} if absent
     * @throws io.jsonwebtoken.JwtException if the token fails verification
     */
    public String getTier(String token) {
        return parseClaims(token).get("tier", String.class);
    }

    /**
     * Returns the {@code jti} claim — the JWT ID. Used by Sprint 8 AU-3
     * revocation denylist. Returns null for legacy tokens issued before AU-3
     * (which didn't carry jti); callers must treat null as "not in denylist".
     *
     * @param token a verified token
     * @return the {@code jti} claim, or {@code null} for legacy tokens without one
     * @throws io.jsonwebtoken.JwtException if the token fails verification
     */
    public String getJti(String token) {
        return parseClaims(token).getId();
    }

    /**
     * Returns the expiration in epoch seconds, for callers (logout endpoint)
     * that need to set a Redis TTL matching the remaining JWT lifetime.
     *
     * <p>Subtracting "now" from this value yields the denylist TTL so a revoked
     * {@code jti} is evicted from Redis exactly when the token would have
     * expired anyway (see {@link JwtRevocationService#revoke(String, long)}).
     *
     * @param token a verified token
     * @return the {@code exp} claim as seconds since the Unix epoch
     * @throws io.jsonwebtoken.JwtException if the token fails verification
     */
    public long getExpiryEpochSeconds(String token) {
        return parseClaims(token).getExpiration().toInstant().getEpochSecond();
    }
}
