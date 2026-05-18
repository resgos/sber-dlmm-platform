package com.sber.dlmm.transaction.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sprint 5 #5.12 — pure unit test on the fee + НДС split. Package-private
 * static method, no Spring needed.
 *
 * <p>The exact rounding (floor at each step) is intentional accounting
 * choice — confirmed with BA. Tests pin the policy.
 */
class FeeSplitTest {

    private static final int FEE_BPS = 5;       // 0.05% default
    private static final short VAT_PCT = 20;     // стандартная ставка

    @Test
    @DisplayName("1M amount × 5bps fee → 500 gross / 83 НДС / 417 net")
    void oneMillionStandardSplit() {
        // gross = 1_000_000 × 5 / 10000 = 500
        // vat   = 500 × 20 / 120 = 83 (floor of 83.333)
        // net   = 500 - 83 = 417
        var split = B2BSettlementService.computeFeeSplit(1_000_000L, FEE_BPS, VAT_PCT);
        assertEquals(500L, split.gross());
        assertEquals(83L, split.vat());
        assertEquals(417L, split.net());
        assertEquals(split.gross(), split.vat() + split.net(), "gross MUST equal vat + net");
    }

    @Test
    @DisplayName("10M amount × 5bps → 5000 gross / 833 НДС / 4167 net")
    void tenMillionStandardSplit() {
        var split = B2BSettlementService.computeFeeSplit(10_000_000L, FEE_BPS, VAT_PCT);
        assertEquals(5000L, split.gross());
        assertEquals(833L, split.vat()); // floor(5000 × 20 / 120) = floor(833.33)
        assertEquals(4167L, split.net());
    }

    @Test
    @DisplayName("Zero amount → zero everything (defensive)")
    void zeroAmount() {
        var split = B2BSettlementService.computeFeeSplit(0L, FEE_BPS, VAT_PCT);
        assertEquals(0L, split.gross());
        assertEquals(0L, split.vat());
        assertEquals(0L, split.net());
    }

    @Test
    @DisplayName("Negative amount → zero (defensive — shouldn't happen post-validation)")
    void negativeAmount() {
        var split = B2BSettlementService.computeFeeSplit(-100L, FEE_BPS, VAT_PCT);
        assertEquals(0L, split.gross());
        assertEquals(0L, split.vat());
        assertEquals(0L, split.net());
    }

    @Test
    @DisplayName("Zero fee bps → zero everything (admin can disable fees per pool)")
    void zeroFeeBps() {
        var split = B2BSettlementService.computeFeeSplit(1_000_000L, 0, VAT_PCT);
        assertEquals(0L, split.gross());
        assertEquals(0L, split.vat());
        assertEquals(0L, split.net());
    }

    @Test
    @DisplayName("Льготная ставка 10%: 500 gross splits to 45 НДС / 455 net")
    void preferentialVatRate() {
        // vat = 500 × 10 / 110 = 45 (floor of 45.45)
        var split = B2BSettlementService.computeFeeSplit(1_000_000L, FEE_BPS, (short) 10);
        assertEquals(500L, split.gross());
        assertEquals(45L, split.vat());
        assertEquals(455L, split.net());
        assertEquals(split.gross(), split.vat() + split.net());
    }

    @Test
    @DisplayName("Zero НДС rate (e.g. exported services): all gross becomes net")
    void zeroVatRate() {
        var split = B2BSettlementService.computeFeeSplit(1_000_000L, FEE_BPS, (short) 0);
        assertEquals(500L, split.gross());
        assertEquals(0L, split.vat());
        assertEquals(500L, split.net());
    }

    @Test
    @DisplayName("Invariant: gross = vat + net for ALL valid inputs (floor preserves)")
    void invariantGrossEqualsVatPlusNet() {
        long[] amounts = {1L, 100L, 1_234L, 999_999L, 1_000_000L, 1_234_567L, 100_000_000L};
        int[] feeBpsVals = {1, 5, 30, 100, 500};
        short[] vatPcts = {0, 10, 20};
        for (long a : amounts) for (int b : feeBpsVals) for (short v : vatPcts) {
            var split = B2BSettlementService.computeFeeSplit(a, b, v);
            assertEquals(split.gross(), split.vat() + split.net(),
                    "INVARIANT VIOLATED for amount=" + a + " bps=" + b + " vat=" + v);
        }
    }

    @Test
    @DisplayName("Realistic example for Sber Treasury 100M RUB transfer at 5bps")
    void sberTreasuryRealisticExample() {
        // 100M RUB at 5bps fee → 50_000 ₽ gross fee
        //   НДС 20% от gross → floor(50000 × 20/120) = 8333 ₽
        //   net → 41667 ₽ DLMM revenue
        var split = B2BSettlementService.computeFeeSplit(100_000_000L, 5, (short) 20);
        assertEquals(50_000L, split.gross());
        assertEquals(8_333L, split.vat());
        assertEquals(41_667L, split.net());
    }
}
