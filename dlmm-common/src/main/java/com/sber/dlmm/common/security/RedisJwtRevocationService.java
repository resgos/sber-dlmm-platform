package com.sber.dlmm.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis-backed {@link JwtRevocationService}. Per-jti key with TTL = remaining
 * token lifetime — Redis evicts the entry when the JWT would have expired
 * naturally, so no cleanup job is needed.
 *
 * <p>Key shape: {@code dlmm:revoked-jti:{jti}} → empty marker. Existence is
 * the signal (SETNX-style semantics via {@code set} + TTL).
 *
 * <p>Auto-registered by {@link DlmmJwtAutoConfiguration} when
 * {@link StringRedisTemplate} is on the classpath.
 */
public class RedisJwtRevocationService implements JwtRevocationService {

    private static final Logger log = LoggerFactory.getLogger(RedisJwtRevocationService.class);
    private static final String KEY_PREFIX = "dlmm:revoked-jti:";
    private static final String MARKER = "1";

    private final StringRedisTemplate redis;

    /**
     * @param redis the shared string Redis template used for denylist reads/writes
     */
    public RedisJwtRevocationService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Checks whether a {@code jti} is on the revocation denylist.
     *
     * <p><b>Fail-open</b> by design: a {@code null}/blank {@code jti} (legacy
     * tokens predating AU-3) and any Redis error both return {@code false}
     * ("not revoked"). A Redis outage therefore degrades to "no revocation
     * enforcement" rather than locking every user out — the accepted gap is a
     * short outage window, not a permanent bypass (Sprint 8 AU-3). Contrast with
     * {@link #revoke(String, long)}, which fails loud.
     *
     * @param jti the JWT ID claim to test; {@code null}/blank ⇒ not revoked
     * @return {@code true} only if a denylist entry definitively exists
     */
    @Override
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) return false;
        try {
            Boolean has = redis.hasKey(KEY_PREFIX + jti);
            return Boolean.TRUE.equals(has);
        } catch (Exception ex) {
            // Fail-OPEN on Redis outage — we don't want to lock everyone out
            // because Redis hiccupped. Sprint 8 AU-3 acceptance accepts this:
            // the gap is a short outage window, not a permanent bypass.
            log.warn("Redis revocation check failed for jti={} ({}). Treating as not-revoked.", jti, ex.getMessage());
            return false;
        }
    }

    /**
     * Writes a denylist entry for {@code jti} that auto-expires after
     * {@code ttlSeconds}, so Redis evicts it exactly when the token would have
     * expired (no cleanup job).
     *
     * <p><b>Fail-loud</b> (opposite of {@link #isRevoked(String)}): if the Redis
     * write fails, this throws so the logout endpoint can surface a 500 rather
     * than silently leaving a "logged-out" token usable. A blank {@code jti} is
     * refused with a warning; a non-positive TTL is skipped (the token has
     * already expired, so there is nothing to deny).
     *
     * @param jti the JWT ID to revoke; blank values are ignored
     * @param ttlSeconds denylist lifetime — MUST be ≥ the token's remaining
     *                   lifetime or the revocation leaks; ≤ 0 is a no-op
     * @throws IllegalStateException if the Redis write fails
     */
    @Override
    public void revoke(String jti, long ttlSeconds) {
        if (jti == null || jti.isBlank()) {
            log.warn("Refusing to revoke blank jti");
            return;
        }
        if (ttlSeconds <= 0) {
            log.debug("jti={} has non-positive TTL ({}s) — token already expired, skipping denylist write", jti, ttlSeconds);
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + jti, MARKER, Duration.ofSeconds(ttlSeconds));
            log.info("Revoked jti={} for {}s", jti, ttlSeconds);
        } catch (Exception ex) {
            // Fail-LOUD on revoke failure — if we couldn't write the denylist
            // entry, logout silently failed. Caller (user-service logout
            // endpoint) catches and surfaces a 500 so the client knows.
            log.error("Failed to revoke jti={}: {}", jti, ex.getMessage());
            throw new IllegalStateException("Failed to record JWT revocation", ex);
        }
    }
}
