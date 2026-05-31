package com.sber.dlmm.oracle.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the pure parsing helpers of MarketDataClient (no network). The CBR metals
 * endpoint ships comma-decimal numbers ("10 464,39") and the 24h change must be
 * computed the same way for every source.
 */
class MarketDataClientTest {

    @Test
    @DisplayName("parseCbrNumber handles comma decimal + nbsp/space thousands")
    void parseCbrNumber() {
        assertEquals(new BigDecimal("10464.39"), MarketDataClient.parseCbrNumber("10464,39"));
        assertEquals(new BigDecimal("10464.39"), MarketDataClient.parseCbrNumber("10 464,39"));
        assertEquals(new BigDecimal("173.58"), MarketDataClient.parseCbrNumber("173,58"));
        assertNull(MarketDataClient.parseCbrNumber(null));
        assertNull(MarketDataClient.parseCbrNumber(""));
        assertNull(MarketDataClient.parseCbrNumber("n/a"));
    }

    @Test
    @DisplayName("pctChange computes the 24h delta, 0 on missing/zero prior")
    void pctChange() {
        // 100 → 105 = +5%
        assertEquals(0, new BigDecimal("5.0000").compareTo(
                MarketDataClient.pctChange(new BigDecimal("100"), new BigDecimal("105"))));
        // 200 → 190 = -5%
        assertEquals(0, new BigDecimal("-5.0000").compareTo(
                MarketDataClient.pctChange(new BigDecimal("200"), new BigDecimal("190"))));
        assertEquals(BigDecimal.ZERO, MarketDataClient.pctChange(null, new BigDecimal("105")));
        assertEquals(BigDecimal.ZERO, MarketDataClient.pctChange(BigDecimal.ZERO, new BigDecimal("105")));
    }
}
