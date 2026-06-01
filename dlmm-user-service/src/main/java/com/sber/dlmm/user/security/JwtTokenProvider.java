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

/**
 * The platform's JWT <b>issuer</b>. This is the only component that mints
 * tokens; every other service (and the gateway) merely validates them via the
 * shared {@code JwtAuthenticationFilter} in {@code dlmm-common}. The signing
 * secret here must be byte-for-byte identical to the validators' secret, or
 * every downstream request fails signature verification.
 *
 * <p>Token model:
 * <ul>
 *   <li><b>Access token</b> — short-lived (config {@code access-token-expiry-minutes},
 *       ~30&nbsp;min). Subject = user id; claims carry {@code role},
 *       {@code kycStatus} and, when the caller has an org, {@code orgId} +
 *       {@code orgRole}. Used to authorize business requests.</li>
 *   <li><b>Refresh token</b> — long-lived (config {@code refresh-token-expiry-days},
 *       7&nbsp;days), marked with {@code type=refresh}. Exchanged at
 *       {@code /auth/refresh} for a new pair; rejected by the common filter on
 *       business endpoints.</li>
 * </ul>
 *
 * <p>Both token types include a random {@code jti} (JWT id) so a logout can
 * write that id to the Redis revocation denylist for the token's remaining
 * lifetime. The HMAC algorithm (HS384 vs HS512) is selected by the JJWT
 * library from the key length derived from {@link #secret}.
 *
 * <p>Thread-safety: the {@link #secretKey} is built once in {@link #init()}
 * and never mutated; all token operations are stateless and safe to call
 * concurrently.
 */
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

    /**
     * Derives the HMAC signing key from the configured secret once the bean's
     * {@code @Value} fields are injected. Runs at startup via
     * {@code @PostConstruct}; the resulting {@link #secretKey} is reused for
     * every sign and verify operation.
     *
     * <p>{@code SecretValidationOnStartup} (in {@code dlmm-common}) has already
     * aborted boot if the secret is missing or shorter than 32 bytes, so the
     * key here is guaranteed long enough for the HMAC-SHA algorithm.
     */
    @PostConstruct
    public void init() {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Convenience overload that issues an access token <b>without</b> org
     * claims — delegates to the five-arg form with null {@code orgId}/
     * {@code orgRole}. Used by callers that don't resolve org membership.
     *
     * @param userId    the authenticated user's id (becomes the JWT subject)
     * @param role      the user's platform role (embedded as the {@code role} claim)
     * @param kycStatus the user's KYC status (embedded as the {@code kycStatus} claim)
     * @return a signed, compact access-token string
     */
    public String generateAccessToken(UUID userId, UserRole role, KycStatus kycStatus) {
        return generateAccessToken(userId, role, kycStatus, null, null);
    }

    /**
     * Sprint 11 G-21 — overload that embeds the caller's
     * organisation membership when present. {@code orgId} /
     * {@code orgRole} may be null (user has no org), in which case
     * the claims are omitted; the gateway filter treats absent
     * claims as "no org" without erroring.
     *
     * <p>Mints a fresh access token: random {@code jti}, subject = user id,
     * {@code role} + {@code kycStatus} claims always present, {@code orgId} +
     * {@code orgRole} added only when non-null, issued-at = now, expiry = now +
     * {@code access-token-expiry-minutes}, signed with {@link #secretKey}.
     *
     * @param userId    the authenticated user's id (becomes the JWT subject)
     * @param role      the user's platform role ({@code role} claim)
     * @param kycStatus the user's KYC status ({@code kycStatus} claim)
     * @param orgId     the user's organisation id, or null to omit the claim
     * @param orgRole   the user's role within that org, or null to omit the claim
     * @return a signed, compact access-token string
     */
    public String generateAccessToken(UUID userId, UserRole role, KycStatus kycStatus,
                                       UUID orgId, String orgRole) {
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofMinutes(accessTokenExpiryMinutes));

        // Sprint 8 AU-3 — jti enables denylist on logout. Without it, revocation
        // would have no key to write against (Redis SET membership keyed by jti).
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("role", role.name())
                .claim("kycStatus", kycStatus.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry));
        if (orgId != null) {
            builder.claim("orgId", orgId.toString());
        }
        if (orgRole != null) {
            builder.claim("orgRole", orgRole);
        }
        return builder.signWith(secretKey).compact();
    }

    /**
     * Mints a refresh token for the given user: random {@code jti}, subject =
     * user id, {@code type=refresh} marker claim, expiry = now +
     * {@code refresh-token-expiry-days} (7&nbsp;days). Carries no role/KYC
     * claims — those are re-read from the database when the refresh is
     * exchanged, so a refreshed access token always reflects current state.
     *
     * @param userId the user the refresh token is bound to (JWT subject)
     * @return a signed, compact refresh-token string
     */
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

    /**
     * The access-token lifetime in <b>seconds</b>, derived from the configured
     * minutes. Returned to clients in the auth response as {@code expiresIn} so
     * they know when to refresh.
     *
     * @return access-token validity in seconds
     */
    public long getAccessTokenExpirySeconds() {
        return accessTokenExpiryMinutes * 60;
    }

    /**
     * Tests whether a token is well-formed, correctly signed by this issuer's
     * key, and not expired. Does <b>not</b> consult the revocation denylist or
     * check token type — callers layer those checks separately. Any parse or
     * signature failure is swallowed and logged at WARN, returning false rather
     * than throwing, so callers can branch on a simple boolean.
     *
     * @param token the compact JWT string to verify
     * @return true if the signature and expiry are valid; false otherwise
     */
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

    /**
     * Verifies the signature and returns the token's claim set. Unlike
     * {@link #validateToken}, this <b>throws</b> on an invalid or expired
     * token, so call it only after validation or where a failure should
     * propagate. Used to read the {@code jti} and expiry during logout
     * revocation.
     *
     * @param token the compact JWT string to parse
     * @return the verified {@link Claims} payload
     * @throws io.jsonwebtoken.JwtException if the token is malformed, has a bad
     *                                      signature, or is expired
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Extracts the user id (JWT subject) from a verified token.
     *
     * @param token the compact JWT string
     * @return the user id parsed from the {@code sub} claim
     * @throws io.jsonwebtoken.JwtException if the token fails verification
     * @throws IllegalArgumentException     if the subject is not a valid UUID
     */
    public UUID getUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    /**
     * Extracts the platform role from a verified token's {@code role} claim.
     *
     * @param token the compact JWT string
     * @return the {@link UserRole} carried by the token
     * @throws io.jsonwebtoken.JwtException if the token fails verification
     * @throws IllegalArgumentException     if the claim is absent or not a known role
     */
    public UserRole getRole(String token) {
        return UserRole.valueOf(parseClaims(token).get("role", String.class));
    }

    /**
     * Extracts the KYC status from a verified token's {@code kycStatus} claim.
     *
     * @param token the compact JWT string
     * @return the {@link KycStatus} carried by the token
     * @throws io.jsonwebtoken.JwtException if the token fails verification
     * @throws IllegalArgumentException     if the claim is absent or not a known status
     */
    public KycStatus getKycStatus(String token) {
        return KycStatus.valueOf(parseClaims(token).get("kycStatus", String.class));
    }

    /**
     * Distinguishes a refresh token from an access token by inspecting the
     * {@code type} claim. Used at {@code /auth/refresh} to reject a misused
     * access token. Verifies the signature as a side effect (via
     * {@link #parseClaims}).
     *
     * @param token the compact JWT string
     * @return true if the {@code type} claim equals {@code "refresh"}
     * @throws io.jsonwebtoken.JwtException if the token fails verification
     */
    public boolean isRefreshToken(String token) {
        Claims claims = parseClaims(token);
        return "refresh".equals(claims.get("type", String.class));
    }
}
