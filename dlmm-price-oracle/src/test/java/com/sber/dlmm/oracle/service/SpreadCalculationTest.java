package com.sber.dlmm.oracle.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sprint 5 #5.9 — pure unit test on the spread calculation. Visible
 * package-private static method, no Spring / JPA needed.
 */
class SpreadCalculationTest {

    @Test
    @DisplayName("DLMM above CBR by 1% reports +100 bps")
    void positiveSpreadOnePercent() {
        BigDecimal dlmm = new BigDecimal("90.0000");
        BigDecimal cbr = new BigDecimal("89.1089");  // ~1% lower
        BigDecimal bps = PriceOracleService.computeSpreadBps(dlmm, cbr);
        assertEquals(new BigDecimal("100.00"), bps);
    }

    @Test
    @DisplayName("DLMM below CBR by 50bps reports -50 bps")
    void negativeSpreadHalfPercent() {
        BigDecimal cbr = new BigDecimal("100.0000");
        BigDecimal dlmm = new BigDecimal("99.5000");
        BigDecimal bps = PriceOracleService.computeSpreadBps(dlmm, cbr);
        assertEquals(new BigDecimal("-50.00"), bps);
    }

    @Test
    @DisplayName("DLMM = CBR reports 0 bps")
    void zeroSpread() {
        BigDecimal v = new BigDecimal("75.1234");
        assertEquals(new BigDecimal("0.00"), PriceOracleService.computeSpreadBps(v, v));
    }

    @Test
    @DisplayName("CBR rate null or non-positive guards against div-by-zero — returns null")
    void cbrZeroOrNullGuard() {
        BigDecimal dlmm = new BigDecimal("100");
        assertNull(PriceOracleService.computeSpreadBps(dlmm, null));
        assertNull(PriceOracleService.computeSpreadBps(dlmm, BigDecimal.ZERO));
        assertNull(PriceOracleService.computeSpreadBps(dlmm, new BigDecimal("-1")));
    }

    @Test
    @DisplayName("Realistic USD example: DLMM 89.5 vs CBR 89.1234 ≈ +42 bps")
    void realisticUsdExample() {
        BigDecimal dlmm = new BigDecimal("89.5000");
        BigDecimal cbr = new BigDecimal("89.1234");
        BigDecimal bps = PriceOracleService.computeSpreadBps(dlmm, cbr);
        // 89.5 / 89.1234 - 1 = 0.00422562... × 10000 = 42.2562... bps
        // HALF_UP at scale 2 → 42.26 (the 6 in third decimal rounds up the 5).
        assertEquals(new BigDecimal("42.26"), bps);
    }
}
