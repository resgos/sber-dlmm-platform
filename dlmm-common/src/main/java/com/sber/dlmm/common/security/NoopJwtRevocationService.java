package com.sber.dlmm.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fallback {@link JwtRevocationService} for services without Redis on the
 * classpath. Logs a one-time WARN at first call so production deployments
 * can't silently lose JWT revocation if the Redis starter goes missing
 * during a refactor.
 *
 * <p>{@link #revoke(String, long)} is a no-op but does NOT throw — callers
 * (the user-service logout endpoint) can be stack-traced through it in
 * tests, but in production this bean only loads where Redis is absent
 * (i.e. not on user-service which is where {@code revoke} actually fires).
 */
public class NoopJwtRevocationService implements JwtRevocationService {

    private static final Logger log = LoggerFactory.getLogger(NoopJwtRevocationService.class);
    private final AtomicBoolean warnedRevoke = new AtomicBoolean(false);
    private final AtomicBoolean warnedCheck = new AtomicBoolean(false);

    @Override
    public boolean isRevoked(String jti) {
        if (warnedCheck.compareAndSet(false, true)) {
            log.warn("JwtRevocationService is a NO-OP on this service — JWT revocation will not be enforced. "
                    + "Add spring-boot-starter-data-redis if you need denylist support.");
        }
        return false;
    }

    @Override
    public void revoke(String jti, long ttlSeconds) {
        if (warnedRevoke.compareAndSet(false, true)) {
            log.warn("JwtRevocationService.revoke called on NO-OP impl — token jti={} NOT revoked. "
                    + "This service is missing spring-data-redis on the classpath.", jti);
        }
    }
}
