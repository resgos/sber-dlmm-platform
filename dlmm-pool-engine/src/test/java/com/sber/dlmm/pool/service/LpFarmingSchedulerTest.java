package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.LiquidityStrategy;
import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.entity.LpPosition;
import com.sber.dlmm.pool.entity.PoolRewardsConfig;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.LpPositionRepository;
import com.sber.dlmm.pool.repository.PoolRewardsConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LpFarmingSchedulerTest {

    @Mock PoolRewardsConfigRepository configRepository;
    @Mock LiquidityPoolRepository poolRepository;
    @Mock LpPositionRepository positionRepository;
    @Mock LpFarmingService farmingService;

    private static final UUID POOL = UUID.randomUUID();
    private static final UUID REWARD_TOKEN = UUID.randomUUID();
    private static final UUID USER_A = UUID.randomUUID();
    private static final UUID USER_B = UUID.randomUUID();

    /** Scheduler with farming enabled and a configurable cycle length (ms). */
    private LpFarmingScheduler scheduler(long cycleMs) {
        return new LpFarmingScheduler(configRepository, poolRepository, positionRepository,
                farmingService, true, cycleMs);
    }

    private PoolRewardsConfig config(long emissionPerDay) {
        return PoolRewardsConfig.builder()
                .id(UUID.randomUUID()).poolId(POOL).rewardTokenId(REWARD_TOKEN)
                .emissionPerDay(emissionPerDay).enabled(true).build();
    }

    private LiquidityPool poolAtBin(int activeBin) {
        return LiquidityPool.builder()
                .id(POOL).tokenXId(UUID.randomUUID()).tokenYId(UUID.randomUUID())
                .status(PoolStatus.ACTIVE).activeBinId(activeBin).basePrice(BigDecimal.ONE)
                .build();
    }

    private LpPosition position(UUID id, UUID userId, int min, int max, long shares) {
        return LpPosition.builder()
                .id(id).userId(userId).poolId(POOL).binRangeMin(min).binRangeMax(max)
                .strategy(LiquidityStrategy.SPOT).totalLiquidityShares(shares).isActive(true)
                .build();
    }

    @Test
    void splitsCycleEmissionProportionallyAcrossInRangePositions() {
        // Full-day cycle → cycleEmission == emissionPerDay (1000), no proration factor.
        UUID posA = UUID.randomUUID();
        UUID posB = UUID.randomUUID();
        when(configRepository.findByEnabledTrue()).thenReturn(List.of(config(1_000)));
        when(poolRepository.findById(POOL)).thenReturn(Optional.of(poolAtBin(0)));
        when(positionRepository.findByPoolIdAndIsActiveTrue(POOL)).thenReturn(List.of(
                position(posA, USER_A, -5, 5, 3_000),   // in-range, 3/4
                position(posB, USER_B, -5, 5, 1_000)    // in-range, 1/4
        ));

        scheduler(86_400_000L).accrueRewards();

        verify(farmingService).accruePosition(posA, POOL, USER_A, REWARD_TOKEN, 750L); // 1000 * 3000/4000
        verify(farmingService).accruePosition(posB, POOL, USER_B, REWARD_TOKEN, 250L); // 1000 * 1000/4000
    }

    @Test
    void proratesDailyEmissionToHourlyCycle() {
        // Hourly cycle → cycleEmission = 24000 * 3_600_000 / 86_400_000 = 1000.
        UUID posA = UUID.randomUUID();
        when(configRepository.findByEnabledTrue()).thenReturn(List.of(config(24_000)));
        when(poolRepository.findById(POOL)).thenReturn(Optional.of(poolAtBin(0)));
        when(positionRepository.findByPoolIdAndIsActiveTrue(POOL)).thenReturn(List.of(
                position(posA, USER_A, -5, 5, 5_000)    // sole in-range LP → whole cycle emission
        ));

        scheduler(3_600_000L).accrueRewards();

        verify(farmingService).accruePosition(posA, POOL, USER_A, REWARD_TOKEN, 1_000L);
    }

    @Test
    void excludesOutOfRangePositions() {
        // active bin 100 is outside posA's [-5,5]; only posB ([90,110]) is in-range.
        UUID posA = UUID.randomUUID();
        UUID posB = UUID.randomUUID();
        when(configRepository.findByEnabledTrue()).thenReturn(List.of(config(1_000)));
        when(poolRepository.findById(POOL)).thenReturn(Optional.of(poolAtBin(100)));
        when(positionRepository.findByPoolIdAndIsActiveTrue(POOL)).thenReturn(List.of(
                position(posA, USER_A, -5, 5, 9_000),     // OUT of range
                position(posB, USER_B, 90, 110, 1_000)    // in-range, sole → whole emission
        ));

        scheduler(86_400_000L).accrueRewards();

        verify(farmingService, never()).accruePosition(eq(posA), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(farmingService).accruePosition(posB, POOL, USER_B, REWARD_TOKEN, 1_000L);
    }

    @Test
    void boundaryBinsAreInRange() {
        // active bin exactly equals binRangeMax → still eligible (inclusive range).
        UUID posA = UUID.randomUUID();
        when(configRepository.findByEnabledTrue()).thenReturn(List.of(config(1_000)));
        when(poolRepository.findById(POOL)).thenReturn(Optional.of(poolAtBin(5)));
        when(positionRepository.findByPoolIdAndIsActiveTrue(POOL)).thenReturn(List.of(
                position(posA, USER_A, 0, 5, 1_000)
        ));

        scheduler(86_400_000L).accrueRewards();

        verify(farmingService).accruePosition(posA, POOL, USER_A, REWARD_TOKEN, 1_000L);
    }

    @Test
    void noAccrualWhenNoPositionsInRange() {
        when(configRepository.findByEnabledTrue()).thenReturn(List.of(config(1_000)));
        when(poolRepository.findById(POOL)).thenReturn(Optional.of(poolAtBin(0)));
        when(positionRepository.findByPoolIdAndIsActiveTrue(POOL)).thenReturn(List.of(
                position(UUID.randomUUID(), USER_A, 50, 60, 1_000)  // out of range
        ));

        scheduler(86_400_000L).accrueRewards();

        verify(farmingService, never()).accruePosition(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void disabledSchedulerDoesNothing() {
        LpFarmingScheduler disabled = new LpFarmingScheduler(configRepository, poolRepository,
                positionRepository, farmingService, false, 3_600_000L);

        disabled.accrueRewards();

        verifyNoInteractions(configRepository, poolRepository, positionRepository, farmingService);
    }
}
