package com.sber.dlmm.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sprint 9-DS-r4 (P0-1) — pins the canonical LB-DLMM math
 * ({@link LbDlmmMath}) so the Sprint 10 migration off mixed-unit
 * accounting has a fixed contract to land on.
 *
 * <p>Cases focus on the invariants that mixed-unit accounting
 * (today's {@code liquidity = amountX + amountY}) violates:
 *   <ul>
 *     <li>composition stays in [0,1] without any tactical clamp</li>
 *     <li>roundtrip L → (x,y) → L is stable (modulo integer floor)</li>
 *     <li>scaling reserves preserves composition</li>
 *   </ul>
 */
class LbDlmmMathTest {

    @Nested
    @DisplayName("liquidity()")
    class LiquidityFormula {

        @Test
        @DisplayName("Y-only bin: L = reserveY")
        void yOnlyBin() {
            BigDecimal price = new BigDecimal("5000000");
            BigDecimal L = LbDlmmMath.liquidity(0, 100_000, price);
            assertThat(L).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("X-only bin: L = reserveX * binPrice")
        void xOnlyBin() {
            BigDecimal price = new BigDecimal("100");
            BigDecimal L = LbDlmmMath.liquidity(50, 0, price);
            assertThat(L).isEqualByComparingTo("5000");
        }

        @Test
        @DisplayName("mixed bin: L = x*p + y")
        void mixedBin() {
            BigDecimal price = new BigDecimal("100");
            BigDecimal L = LbDlmmMath.liquidity(50, 1_000, price);
            // 50*100 + 1000 = 6000
            assertThat(L).isEqualByComparingTo("6000");
        }

        @Test
        @DisplayName("rejects non-positive binPrice")
        void rejectsBadPrice() {
            assertThatThrownBy(() -> LbDlmmMath.liquidity(1, 1, BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LbDlmmMath.liquidity(1, 1, new BigDecimal("-1")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("compositionFactor()")
    class CompositionFactor {

        @Test
        @DisplayName("Y-only bin: c = 1")
        void allY() {
            BigDecimal L = LbDlmmMath.liquidity(0, 1_000, new BigDecimal("100"));
            assertThat(LbDlmmMath.compositionFactor(1_000, L)).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("X-only bin: c = 0")
        void allX() {
            BigDecimal L = LbDlmmMath.liquidity(10, 0, new BigDecimal("100"));
            assertThat(LbDlmmMath.compositionFactor(0, L)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("mixed bin: c stays in [0,1] under canonical accounting")
        void mixed_alwaysInRange() {
            // Real seed pool numbers — SBTC at 5M ₽, 1 SBTC + 5M SRUB
            BigDecimal price = new BigDecimal("5000000");
            BigDecimal L = LbDlmmMath.liquidity(1, 5_000_000, price);
            BigDecimal c = LbDlmmMath.compositionFactor(5_000_000, L);
            assertThat(c.compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
            assertThat(c.compareTo(BigDecimal.ONE)).isLessThanOrEqualTo(0);
            // Y is half of L (1 SBTC * 5M + 5M = 10M), so c = 0.5
            assertThat(c).isEqualByComparingTo("0.5");
        }

        @Test
        @DisplayName("zero liquidity returns zero (empty bin)")
        void zeroLiquidity() {
            assertThat(LbDlmmMath.compositionFactor(0, BigDecimal.ZERO))
                    .isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("reserve roundtrip")
    class Roundtrip {

        @Test
        @DisplayName("(x,y) → L,c → (x,y) recovers reserves at nice prices")
        void roundtrip_nicePrice() {
            BigDecimal price = new BigDecimal("100");
            long reserveX = 50;
            long reserveY = 1_000;

            BigDecimal L = LbDlmmMath.liquidity(reserveX, reserveY, price);
            BigDecimal c = LbDlmmMath.compositionFactor(reserveY, L);

            long recoveredX = LbDlmmMath.reserveX(L, c, price);
            long recoveredY = LbDlmmMath.reserveY(L, c);

            assertThat(recoveredX).isEqualTo(reserveX);
            assertThat(recoveredY).isEqualTo(reserveY);
        }

        @Test
        @DisplayName("composition is invariant under reserve scaling")
        void compositionInvariantUnderScaling() {
            BigDecimal price = new BigDecimal("200.5");
            BigDecimal L1 = LbDlmmMath.liquidity(7, 1_400, price);
            BigDecimal c1 = LbDlmmMath.compositionFactor(1_400, L1);

            // Scale both reserves by 10×
            BigDecimal L10 = LbDlmmMath.liquidity(70, 14_000, price);
            BigDecimal c10 = LbDlmmMath.compositionFactor(14_000, L10);

            // c should be identical (within DECIMAL128 precision)
            assertThat(c1.round(new MathContext(20)))
                    .isEqualByComparingTo(c10.round(new MathContext(20)));
        }
    }
}
