package com.sber.dlmm.common.enums;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for {@link TokenType}. The enum was extended with
 * FIAT_BACKED, COMMODITY_BACKED, UTILITY, INDEX_TOKEN to match seed data
 * (Russian stocks + commodities + FX). This test pins:
 *
 *  1. The full set of accepted names — adding/removing a value here forces
 *     a deliberate update to seed (init-db.sql), Liquibase changelogs and
 *     anything switching on the enum.
 *  2. Round-trip JSON serialisation for every value, since the enum is
 *     exposed in REST responses (Token DTO).
 *  3. valueOf for every name a controller might receive — guards against
 *     a request body with a known seed value silently producing 500.
 *
 * If you grow the enum: extend EXPECTED_NAMES and the JSON test below.
 * If a value is no longer used by any seed/migration: remove it AND
 * delete its row in this test.
 */
class TokenTypeTest {

    private static final String[] EXPECTED_NAMES = {
            // Original 4 — engine-level classification
            "EQUITY_TOKEN", "STABLE_TOKEN", "LP_TOKEN", "GOVERNANCE_TOKEN",
            // Added for extended seed catalog — descriptive aliases
            "FIAT_BACKED", "COMMODITY_BACKED", "UTILITY", "INDEX_TOKEN"
    };

    @Test
    void allExpectedValuesArePresent() {
        for (String name : EXPECTED_NAMES) {
            assertThat(TokenType.valueOf(name))
                    .as("TokenType.%s must exist (seed/init-db.sql references it)", name)
                    .isNotNull();
        }
    }

    @Test
    void enumHasNoOrphanValues() {
        assertThat(TokenType.values())
                .as("TokenType.values() should match the curated EXPECTED_NAMES list — "
                        + "if you added a value, update EXPECTED_NAMES; if you removed one, "
                        + "make sure no seed / changelog / DTO still references it.")
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(EXPECTED_NAMES);
    }

    @Test
    void everyValueRoundTripsJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (TokenType value : TokenType.values()) {
            String json = mapper.writeValueAsString(value);
            assertThat(json)
                    .as("JSON for %s should be quoted name", value)
                    .isEqualTo("\"" + value.name() + "\"");
            TokenType parsed = mapper.readValue(json, TokenType.class);
            assertThat(parsed).isEqualTo(value);
        }
    }

    /**
     * Sprint 9-DS-r4 (TD-7) — pins the off-platform vs synthetic
     * policy decision baked into {@link TokenType#requiresUnderlyingAsset}.
     * A future contributor who adds a value MUST extend the switch
     * (the enum will refuse to compile otherwise), but this test also
     * guards the intent so a careless "all values true" edit gets
     * caught at CI time.
     */
    @Test
    void requiresUnderlyingAsset_matchesPolicy() {
        // Off-platform: tied to a real-world asset.
        assertThat(TokenType.EQUITY_TOKEN.requiresUnderlyingAsset()).isTrue();
        assertThat(TokenType.FIAT_BACKED.requiresUnderlyingAsset()).isTrue();
        assertThat(TokenType.COMMODITY_BACKED.requiresUnderlyingAsset()).isTrue();
        assertThat(TokenType.INDEX_TOKEN.requiresUnderlyingAsset()).isTrue();
        // Synthetic / platform-only.
        assertThat(TokenType.LP_TOKEN.requiresUnderlyingAsset()).isFalse();
        assertThat(TokenType.GOVERNANCE_TOKEN.requiresUnderlyingAsset()).isFalse();
        assertThat(TokenType.UTILITY.requiresUnderlyingAsset()).isFalse();
        assertThat(TokenType.STABLE_TOKEN.requiresUnderlyingAsset()).isFalse();
    }
}
