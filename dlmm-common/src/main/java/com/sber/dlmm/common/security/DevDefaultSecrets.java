package com.sber.dlmm.common.security;

import java.util.Set;

/**
 * Single source of truth for the well-known dev-default JWT secrets that
 * MUST NOT be used in production.
 *
 * <p>Two places consume this list:
 * <ul>
 *     <li>{@link SecretValidationOnStartup} — fails fast on startup if a
 *         service in {@code prod} profile is wired with one of these
 *         values.</li>
 *     <li>Future ops scripts (CI guards, container image scans) — can
 *         import {@link #KNOWN_DEV_DEFAULTS} so the blocklist stays in one
 *         place and additions in {@code docker/.env.example} are picked up
 *         automatically.</li>
 * </ul>
 *
 * <p>Membership rules:
 * <ul>
 *     <li>Anything verbatim from {@code docker/.env.example} or any
 *         committed YAML's documented dev placeholder.</li>
 *     <li>Any literal beginning with {@code change-me} (the historical
 *         placeholder shipped in early {@code application.yml} files
 *         before {@code ${JWT_SECRET:?required}} fail-fast was wired in;
 *         {@link #startsWithKnownDevPrefix(String)} catches future
 *         variants without an exact match).</li>
 * </ul>
 *
 * <p>The minimum HS256 secret size ({@link #MIN_SECRET_BYTES}) lives here
 * too — RFC 7518 §3.2 requires HS256 keys be ≥ 256 bits (32 bytes), and
 * {@code Keys.hmacShaKeyFor(...)} throws {@code WeakKeyException} below
 * that threshold. We surface a friendlier message before jjwt does.
 */
public final class DevDefaultSecrets {

    /** Minimum HS256 key size per RFC 7518 §3.2 (256 bits = 32 bytes). */
    public static final int MIN_SECRET_BYTES = 32;

    /**
     * Exact verbatim dev-default values shipped in {@code docker/.env.example}
     * and historical {@code application.yml} comments. The validator rejects
     * exact matches; {@link #startsWithKnownDevPrefix(String)} catches the
     * {@code change-me-*} family more permissively.
     */
    public static final Set<String> KNOWN_DEV_DEFAULTS = Set.of(
            // docker/.env.example shipped dev JWT secret
            "super-secret-jwt-key-for-dlmm-platform-256-bit-min-dev-only",
            // docker/.env.example shipped dev DB password (not a JWT secret,
            // but included so the same blocklist works for both)
            "dlmm_secret_dev_only",
            // historical literal from pre-fail-fast application.yml files
            "change-me-in-production-please-use-a-real-secret",
            "change-me-in-production",
            "change-me"
    );

    /**
     * Prefixes that flag a value as a documented dev placeholder even if
     * the exact suffix has drifted. Keeps the blocklist resilient to
     * future variants ("change-me-jwt", "change-me-prod", ...).
     */
    private static final Set<String> KNOWN_DEV_PREFIXES = Set.of(
            "change-me"
    );

    private DevDefaultSecrets() {
        // constants only
    }

    /**
     * Returns true if the given value is a known dev-default JWT/DB
     * secret. Null and empty values count as "dev-default" too — they're
     * never valid for prod regardless.
     */
    public static boolean isDevDefault(String secret) {
        if (secret == null || secret.isBlank()) {
            return true;
        }
        if (KNOWN_DEV_DEFAULTS.contains(secret)) {
            return true;
        }
        return startsWithKnownDevPrefix(secret);
    }

    /**
     * Returns true if the secret begins with one of the documented dev
     * placeholder prefixes (e.g. {@code change-me-...}).
     */
    public static boolean startsWithKnownDevPrefix(String secret) {
        if (secret == null) {
            return false;
        }
        for (String prefix : KNOWN_DEV_PREFIXES) {
            if (secret.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
