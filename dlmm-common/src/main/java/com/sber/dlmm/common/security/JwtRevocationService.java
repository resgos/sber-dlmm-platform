package com.sber.dlmm.common.security;

/**
 * Sprint 8 AU-3 — JWT revocation (audit C-5).
 *
 * <p>Before this interface, every issued JWT was valid for its full expiry
 * (default 60 min access, 7d refresh) with no way to terminate a session.
 * Stolen tokens were exploitable until expiry; a user clicking "log out" had
 * no server-side effect.
 *
 * <p>This abstraction lets {@link JwtAuthenticationFilter} consult a denylist
 * keyed by the JWT's {@code jti} claim. The user-service's {@code /auth/logout}
 * endpoint writes to the denylist with TTL = remaining-expiry-seconds, so
 * Redis garbage-collects the entry naturally — no background cleanup needed.
 *
 * <p>Two implementations:
 * <ul>
 *   <li>{@link RedisJwtRevocationService} — auto-registered on services with
 *       spring-data-redis on the classpath (pool-engine, fee-service,
 *       transaction-service, notification-service, plus user-service once we
 *       add the starter for AU-3).</li>
 *   <li>{@link NoopJwtRevocationService} — fallback for services without Redis
 *       (e.g. dlmm-common's own unit-test classpath). Logs a one-time WARN at
 *       startup so it's not silently a no-op in production.</li>
 * </ul>
 *
 * <p>Wire-up details in {@link DlmmJwtAutoConfiguration} (servlet stack) and
 * {@link DlmmWebClientAutoConfiguration} (reactive stack — gateway already
 * checks JWTs but Sprint 9 will mirror this).
 */
public interface JwtRevocationService {

    /**
     * @return true if {@code jti} has been revoked and the request must be
     *         rejected. Null/empty jti returns false (legacy tokens issued
     *         before AU-3 didn't carry jti — they keep working until expiry).
     */
    boolean isRevoked(String jti);

    /**
     * Add {@code jti} to the denylist for {@code ttlSeconds} seconds. The TTL
     * MUST be ≥ the JWT's remaining lifetime — otherwise Redis evicts the
     * entry while the token is still valid → revocation leaks.
     *
     * <p>Idempotent: revoking an already-revoked jti is a no-op (Redis SET
     * overwrites with the same TTL).
     */
    void revoke(String jti, long ttlSeconds);
}
