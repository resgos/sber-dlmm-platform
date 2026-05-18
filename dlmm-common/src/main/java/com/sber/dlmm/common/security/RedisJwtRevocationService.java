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

    public RedisJwtRevocationService(StringRedisTemplate redis) {
        this.redis = redis;
    }

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
