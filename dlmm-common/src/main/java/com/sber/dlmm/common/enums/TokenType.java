package com.sber.dlmm.common.enums;

/**
 * Classification of a token in the token-service catalog.
 *
 * <p>Describes what a token represents (a real-world asset, a stablecoin,
 * a pool LP share, etc.). Drives catalog UX and the
 * {@link #requiresUnderlyingAsset()} validation policy on token creation.
 * The first four are the original core types; the remaining values are
 * descriptive aliases added for the extended seed catalog.
 */
public enum TokenType {
    /** Tokenised equity — represents a real off-platform stock. */
    EQUITY_TOKEN,
    /** Stablecoin pegged to a reference value (e.g. SRUB). */
    STABLE_TOKEN,
    /** Liquidity-provider share token derived from a pool position. */
    LP_TOKEN,
    /** Governance / voting token. */
    GOVERNANCE_TOKEN,
    // Aliases used by seed data + extended catalog (Russian stocks, commodities,
    // FX-pegged tokens). Treated equivalently to EQUITY_TOKEN/STABLE_TOKEN by
    // the engine — distinct values keep the catalog UX descriptive.
    /** Token backed by a fiat currency (FX-pegged). */
    FIAT_BACKED,
    /** Token backed by a commodity (e.g. gold, oil). */
    COMMODITY_BACKED,
    /** Utility token granting access to a platform feature/service. */
    UTILITY,
    /** Token tracking an index / basket of underlying assets. */
    INDEX_TOKEN;

    /**
     * Sprint 9-DS-r4 (TD-7) — token-type policy switch. Returns true
     * for any type that represents an off-platform asset (real stock,
     * commodity, fiat currency, index basket); these all require a
     * non-blank {@code underlyingAsset} on token creation so the
     * catalog UX + price-oracle can tie back to the real world.
     *
     * <p>Returns false for synthetic-only types (LP_TOKEN derived from
     * pool shares, GOVERNANCE_TOKEN, UTILITY, STABLE_TOKEN — historically
     * STABLE_TOKEN may track a fiat but the underlying is implicit in
     * the symbol; not enforced).
     *
     * <p>Before this method existed, the validation in
     * {@code TokenService.createToken} only enforced
     * {@code underlyingAsset} on EQUITY_TOKEN. The extended types
     * added in Sprint 2 (FIAT_BACKED / COMMODITY_BACKED / INDEX_TOKEN)
     * silently accepted tokens without an underlying, leaving the
     * price-oracle unable to resolve them.
     */
    public boolean requiresUnderlyingAsset() {
        return switch (this) {
            case EQUITY_TOKEN, FIAT_BACKED, COMMODITY_BACKED, INDEX_TOKEN -> true;
            case LP_TOKEN, GOVERNANCE_TOKEN, UTILITY, STABLE_TOKEN -> false;
        };
    }
}
