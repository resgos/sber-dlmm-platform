package com.sber.dlmm.common.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 9 #6.6 — pin the ApiTier enum's fallback semantics. Bad / null /
 * unknown claim values must always default to FREE so legacy tokens
 * never get rate-limit-elevated by accident.
 */
class ApiTierTest {

    @Test
    void rateLimitValues_pinnedPerTier() {
        // These numbers drive the gateway's RedisRateLimiter beans — exact
        // values are part of the public API contract. Changes here = ops
        // alert tuning + analyst dashboard refresh.
        assertThat(ApiTier.FREE.getReplenishRate()).isEqualTo(10);
        assertThat(ApiTier.FREE.getBurstCapacity()).isEqualTo(15);
        assertThat(ApiTier.PRO.getReplenishRate()).isEqualTo(100);
        assertThat(ApiTier.PRO.getBurstCapacity()).isEqualTo(150);
        assertThat(ApiTier.ENTERPRISE.getReplenishRate()).isEqualTo(1000);
        assertThat(ApiTier.ENTERPRISE.getBurstCapacity()).isEqualTo(1500);
    }

    @Test
    void fromClaim_validValuesParseExact() {
        assertThat(ApiTier.fromClaim("FREE")).isEqualTo(ApiTier.FREE);
        assertThat(ApiTier.fromClaim("PRO")).isEqualTo(ApiTier.PRO);
        assertThat(ApiTier.fromClaim("ENTERPRISE")).isEqualTo(ApiTier.ENTERPRISE);
    }

    @Test
    void fromClaim_caseInsensitive() {
        assertThat(ApiTier.fromClaim("free")).isEqualTo(ApiTier.FREE);
        assertThat(ApiTier.fromClaim("Pro")).isEqualTo(ApiTier.PRO);
        assertThat(ApiTier.fromClaim("enterprise")).isEqualTo(ApiTier.ENTERPRISE);
    }

    @Test
    void fromClaim_trimsWhitespace() {
        assertThat(ApiTier.fromClaim("  PRO  ")).isEqualTo(ApiTier.PRO);
    }

    @Test
    void fromClaim_nullDefaultsToFree() {
        // Pre-Sprint-9 tokens don't carry the tier claim — must default FREE.
        assertThat(ApiTier.fromClaim(null)).isEqualTo(ApiTier.FREE);
    }

    @Test
    void fromClaim_blankDefaultsToFree() {
        assertThat(ApiTier.fromClaim("")).isEqualTo(ApiTier.FREE);
        assertThat(ApiTier.fromClaim("   ")).isEqualTo(ApiTier.FREE);
    }

    @Test
    void fromClaim_unknownValueDefaultsToFree() {
        // Critical security property — a forged JWT with "tier":"GOD_MODE"
        // must NOT escalate to anything other than FREE.
        assertThat(ApiTier.fromClaim("GOD_MODE")).isEqualTo(ApiTier.FREE);
        assertThat(ApiTier.fromClaim("admin")).isEqualTo(ApiTier.FREE);
        assertThat(ApiTier.fromClaim("PREMIUM")).isEqualTo(ApiTier.FREE);
    }
}
