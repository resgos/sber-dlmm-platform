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
    INDEX_TOKEN
}
