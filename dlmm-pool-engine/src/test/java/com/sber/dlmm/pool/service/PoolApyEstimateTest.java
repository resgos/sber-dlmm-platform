package com.sber.dlmm.pool.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PoolService#estimateApyPercent(long, long, int)} — the
 * pure estimated-APY model. Pins the "model floor" semantics: a funded pool
 * never advertises below its fee-tier turnover baseline, but real 24h volume can
 * push the estimate above it. Guards against the regression where a tiny live
 * reading (seeded TVL ≫ sampled volume) surfaced a misleading ~0.00% on a
 * funded, fee-earning pool.
 */
class PoolApyEstimateTest {

    @Test
    @DisplayName("Empty pool (no TVL) advertises zero yield")
    void emptyPoolIsZero() {
        assertThat(PoolService.estimateApyPercent(0, 5_000, 20)).isEqualByComparingTo("0");
        // Defensive: a negative/garbage TVL is treated as empty, not a crash.
        assertThat(PoolService.estimateApyPercent(-1, 5_000, 20)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("No recent volume falls back to the per-fee-tier model floor")
    void noVolumeUsesModelFloor() {
        // 15 bps → 0.0015 * 0.0333 * 365 * 100 = 1.82%
        assertThat(PoolService.estimateApyPercent(1_000_000, 0, 15)).isEqualByComparingTo("1.82");
        // 20 bps → 2.43%; 30 bps → 3.65% — varies per pool by fee, never uniform.
        assertThat(PoolService.estimateApyPercent(1_000_000, 0, 20)).isEqualByComparingTo("2.43");
        assertThat(PoolService.estimateApyPercent(1_000_000, 0, 30)).isEqualByComparingTo("3.65");
    }

    @Test
    @DisplayName("A funded pool with a sliver of live volume is floored, not ~0.00%")
    void tinyLiveVolumeIsFlooredByModel() {
        // SBER-like seeded shape: huge TVL, modest 24h volume → raw spot ≈ 0.01%,
        // which must be lifted to the 20 bps model floor (2.43%) rather than shown.
        long totalTvl = 139_106_519_700_000L;
        long volume24h = 11_998_839_261L;
        assertThat(PoolService.estimateApyPercent(totalTvl, volume24h, 20))
                .isEqualByComparingTo("2.43");
    }

    @Test
    @DisplayName("Genuinely high real volume pushes the estimate above the floor")
    void highRealVolumeBeatsFloor() {
        // 100% daily turnover at 100 bps → spot 365% ≫ model floor 12.15%.
        BigDecimal apy = PoolService.estimateApyPercent(1_000_000, 1_000_000, 100);
        assertThat(apy).isEqualByComparingTo("365.00");
        assertThat(apy).isGreaterThan(PoolService.estimateApyPercent(1_000_000, 0, 100)); // > floor
    }
}
