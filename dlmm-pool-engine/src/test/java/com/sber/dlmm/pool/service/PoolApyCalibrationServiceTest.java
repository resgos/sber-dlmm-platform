package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.LiquidityStrategy;
import com.sber.dlmm.pool.entity.LpPosition;
import com.sber.dlmm.pool.repository.LpPositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NEW-4 (Batch #3) — pins {@link PoolApyCalibrationService}'s
 * fallback / median / cache contract.
 *
 * <p>Test naming follows the LiquidityServiceTest pattern: human-readable
 * {@code @DisplayName} per behaviour, single subject under test mocked
 * against {@link LpPositionRepository}.
 */
class PoolApyCalibrationServiceTest {

    private LpPositionRepository repository;
    private PoolApyCalibrationService service;

    @BeforeEach
    void setUp() {
        repository = mock(LpPositionRepository.class);
        service = new PoolApyCalibrationService(repository);
    }

    @Test
    @DisplayName("returns default 0.20 when pool has no active positions")
    void emptyPool_returnsDefault() {
        UUID poolId = UUID.randomUUID();
        when(repository.findByPoolIdAndIsActiveTrue(poolId)).thenReturn(List.of());

        BigDecimal apy = service.getPoolTargetApy(poolId);

        assertThat(apy).isEqualByComparingTo(PoolApyCalibrationService.DEFAULT_TARGET_APY);
    }

    @Test
    @DisplayName("returns default 0.20 when pool has fewer than 5 eligible positions")
    void smallSample_returnsDefault() {
        UUID poolId = UUID.randomUUID();
        // 3 eligible positions — below MIN_SAMPLE_SIZE=5.
        List<LpPosition> positions = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            positions.add(eligiblePosition(poolId, 30, 100_000L, 5_000L));
        }
        when(repository.findByPoolIdAndIsActiveTrue(poolId)).thenReturn(positions);

        BigDecimal apy = service.getPoolTargetApy(poolId);

        assertThat(apy).isEqualByComparingTo(PoolApyCalibrationService.DEFAULT_TARGET_APY);
    }

    @Test
    @DisplayName("positions younger than 7 days are excluded from the sample")
    void youngPositions_excluded() {
        UUID poolId = UUID.randomUUID();
        // 4 young (3-day-old) positions — should be filtered out, leaving
        // only 1 eligible 30-day-old position. Sample of 1 < 5 → default.
        List<LpPosition> positions = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            positions.add(eligiblePosition(poolId, 3, 100_000L, 5_000L));
        }
        positions.add(eligiblePosition(poolId, 30, 100_000L, 5_000L));
        when(repository.findByPoolIdAndIsActiveTrue(poolId)).thenReturn(positions);

        BigDecimal apy = service.getPoolTargetApy(poolId);

        // Only 1 of 5 passes age filter → falls back to default.
        assertThat(apy).isEqualByComparingTo(PoolApyCalibrationService.DEFAULT_TARGET_APY);
    }

    @Test
    @DisplayName("positions with zero initial deposit are excluded from the sample")
    void zeroDepositPositions_excluded() {
        UUID poolId = UUID.randomUUID();
        // 4 zero-deposit positions (e.g. closed/fully-removed, edge case)
        // + 1 valid → sample size 1 → default.
        List<LpPosition> positions = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            positions.add(eligiblePosition(poolId, 30, 0L, 1_000L));
        }
        positions.add(eligiblePosition(poolId, 30, 100_000L, 5_000L));
        when(repository.findByPoolIdAndIsActiveTrue(poolId)).thenReturn(positions);

        BigDecimal apy = service.getPoolTargetApy(poolId);

        assertThat(apy).isEqualByComparingTo(PoolApyCalibrationService.DEFAULT_TARGET_APY);
    }

    @Test
    @DisplayName("returns median realised APY when sample has at least 5 eligible positions")
    void enoughSample_returnsMedian() {
        UUID poolId = UUID.randomUUID();
        // 5 positions, all 30 days old, initial deposit 100_000, fees:
        //   1000 (yield 0.01) → APY = 0.01 * (365/30) ≈ 0.1217
        //   2000 (yield 0.02) → APY ≈ 0.2433
        //   3000 (yield 0.03) → APY ≈ 0.3650 — median
        //   4000 (yield 0.04) → APY ≈ 0.4867
        //   5000 (yield 0.05) → APY ≈ 0.6083
        // Median = APY of the 3rd position = 0.0300 * 365/30 = 0.365
        List<LpPosition> positions = new ArrayList<>();
        positions.add(eligiblePosition(poolId, 30, 100_000L, 1_000L));
        positions.add(eligiblePosition(poolId, 30, 100_000L, 2_000L));
        positions.add(eligiblePosition(poolId, 30, 100_000L, 3_000L));
        positions.add(eligiblePosition(poolId, 30, 100_000L, 4_000L));
        positions.add(eligiblePosition(poolId, 30, 100_000L, 5_000L));
        when(repository.findByPoolIdAndIsActiveTrue(poolId)).thenReturn(positions);

        BigDecimal apy = service.getPoolTargetApy(poolId);

        // 0.03 * (365 / 30) = 0.365
        // Allow small fractional tolerance for the 30-day-second conversion;
        // assert within ±0.001.
        assertThat(apy.doubleValue()).isBetween(0.364, 0.366);
    }

    @Test
    @DisplayName("median of even-count sample is average of two middle values")
    void evenSampleSize_averagesMiddleTwo() {
        UUID poolId = UUID.randomUUID();
        // 6 positions: APYs sorted are roughly 0.122 / 0.243 / 0.365 / 0.487 / 0.608 / 0.730
        // Middle two = 0.365 and 0.487; average ≈ 0.426.
        List<LpPosition> positions = new ArrayList<>();
        for (int multiplier = 1; multiplier <= 6; multiplier++) {
            positions.add(eligiblePosition(poolId, 30, 100_000L, 1_000L * multiplier));
        }
        when(repository.findByPoolIdAndIsActiveTrue(poolId)).thenReturn(positions);

        BigDecimal apy = service.getPoolTargetApy(poolId);

        // (0.365 + 0.487) / 2 ≈ 0.426
        assertThat(apy.doubleValue()).isBetween(0.424, 0.428);
    }

    @Test
    @DisplayName("falls back to default when repository throws")
    void repositoryThrows_fallsBackToDefault() {
        UUID poolId = UUID.randomUUID();
        when(repository.findByPoolIdAndIsActiveTrue(poolId))
                .thenThrow(new RuntimeException("database is on fire"));

        BigDecimal apy = service.getPoolTargetApy(poolId);

        assertThat(apy).isEqualByComparingTo(PoolApyCalibrationService.DEFAULT_TARGET_APY);
    }

    @Test
    @DisplayName("returns default when poolId is null without hitting the repository")
    void nullPoolId_returnsDefaultNoRepoCall() {
        BigDecimal apy = service.getPoolTargetApy(null);

        assertThat(apy).isEqualByComparingTo(PoolApyCalibrationService.DEFAULT_TARGET_APY);
        verify(repository, never()).findByPoolIdAndIsActiveTrue(any());
    }

    @Test
    @DisplayName("second call within TTL hits the cache (no extra repository call)")
    void secondCall_servedFromCache() {
        UUID poolId = UUID.randomUUID();
        List<LpPosition> positions = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            positions.add(eligiblePosition(poolId, 30, 100_000L, 1_000L * i));
        }
        when(repository.findByPoolIdAndIsActiveTrue(poolId)).thenReturn(positions);

        BigDecimal first = service.getPoolTargetApy(poolId);
        BigDecimal second = service.getPoolTargetApy(poolId);
        BigDecimal third = service.getPoolTargetApy(poolId);

        assertThat(first).isEqualByComparingTo(second).isEqualByComparingTo(third);
        verify(repository, times(1)).findByPoolIdAndIsActiveTrue(poolId);
    }

    @Test
    @DisplayName("invalidate forces the next call to recompute")
    void invalidate_recomputes() {
        UUID poolId = UUID.randomUUID();
        when(repository.findByPoolIdAndIsActiveTrue(poolId))
                .thenReturn(Collections.emptyList());

        service.getPoolTargetApy(poolId);
        service.invalidate(poolId);
        service.getPoolTargetApy(poolId);

        verify(repository, times(2)).findByPoolIdAndIsActiveTrue(poolId);
    }

    @Test
    @DisplayName("invalidate(null) is a safe no-op")
    void invalidateNull_isNoOp() {
        // Should not throw.
        service.invalidate(null);
    }

    @Test
    @DisplayName("median helper handles single-element list")
    void medianHelper_singleElement() {
        List<BigDecimal> single = new ArrayList<>(List.of(new BigDecimal("0.42")));
        BigDecimal m = PoolApyCalibrationService.median(single);
        assertThat(m).isEqualByComparingTo("0.42");
    }

    /**
     * Build an active position that satisfies the age + deposit guards.
     *
     * @param poolId         pool the position belongs to
     * @param ageDays        days since {@code created_at}; values &lt; 7 are excluded
     * @param initialDeposit per-side initial deposit (X and Y are set equal for symmetry)
     * @param unclaimedFee   per-side unclaimed fee accrual (X and Y are set equal)
     */
    private static LpPosition eligiblePosition(UUID poolId, int ageDays, long initialDeposit, long unclaimedFee) {
        return LpPosition.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .poolId(poolId)
                .binRangeMin(8_388_603)
                .binRangeMax(8_388_613)
                .strategy(LiquidityStrategy.SPOT)
                .totalLiquidityShares(1_000_000L)
                .unclaimedFeeX(unclaimedFee)
                .unclaimedFeeY(unclaimedFee)
                .lastFeeGrowthX(0L)
                .lastFeeGrowthY(0L)
                .initialDepositX(initialDeposit)
                .initialDepositY(initialDeposit)
                .isActive(true)
                .createdAt(LocalDateTime.now().minusDays(ageDays))
                .closedAt(null)
                .build();
    }
}
