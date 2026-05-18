package com.sber.dlmm.common.enums;

/**
 * Sprint 9 #6.6 (M#20) — API access paid tier.
 *
 * <p>Drives rate-limiting at the gateway and feature gating in service
 * layer. The tier is carried in the JWT as the {@code tier} claim
 * (default {@link #FREE} when absent — guarantees backwards-compatibility
 * with pre-Sprint-9 tokens).
 *
 * <p>Tier upgrade flow (future): admin endpoint sets users.api_tier
 * column; on next token refresh the new value is baked into the JWT.
 *
 * <p>Rate-limit values (rps) live here next to the enum so the gateway
 * config + analytics dashboards reference a single source of truth.
 */
public enum ApiTier {
    /** Default tier — public retail UX. 10 rps sustained, 15 burst. */
    FREE(10, 15),

    /** Paid tier — small businesses + power users. 100 rps sustained, 150 burst. */
    PRO(100, 150),

    /** Enterprise tier — institutional + B2B issuers. 1000 rps sustained, 1500 burst. */
    ENTERPRISE(1000, 1500);

    private final int replenishRate;
    private final int burstCapacity;

    ApiTier(int replenishRate, int burstCapacity) {
        this.replenishRate = replenishRate;
        this.burstCapacity = burstCapacity;
    }

    public int getReplenishRate() {
        return replenishRate;
    }

    public int getBurstCapacity() {
        return burstCapacity;
    }

    /**
     * Parses a JWT claim value safely — unknown / null / case mismatch
     * returns {@link #FREE} so legacy tokens never get rate-limit-elevated
     * by accident.
     */
    public static ApiTier fromClaim(String raw) {
        if (raw == null || raw.isBlank()) return FREE;
        try {
            return ApiTier.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return FREE;
        }
    }
}
