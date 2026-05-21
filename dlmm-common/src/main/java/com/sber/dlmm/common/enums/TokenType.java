package com.sber.dlmm.common.enums;

public enum TokenType {
    EQUITY_TOKEN,
    STABLE_TOKEN,
    LP_TOKEN,
    GOVERNANCE_TOKEN,
    // Aliases used by seed data + extended catalog (Russian stocks, commodities,
    // FX-pegged tokens). Treated equivalently to EQUITY_TOKEN/STABLE_TOKEN by
    // the engine — distinct values keep the catalog UX descriptive.
    FIAT_BACKED,
    COMMODITY_BACKED,
    UTILITY,
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
