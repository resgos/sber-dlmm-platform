package com.sber.dlmm.pool.service;

import com.sber.dlmm.pool.dto.FarmRewardSummary;
import com.sber.dlmm.pool.entity.PoolPositionReward;
import com.sber.dlmm.pool.repository.PoolPositionRewardRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LpFarmingServiceTest {

    @Mock PoolPositionRewardRepository rewardRepository;
    @Mock LimitOrderBalanceWriter balanceWriter;
    @InjectMocks LpFarmingService service;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID POOL = UUID.randomUUID();
    private static final UUID POSITION = UUID.randomUUID();
    private static final UUID REWARD_TOKEN = UUID.randomUUID();

    // ── positionShare (pure) ─────────────────────────────────────────────────

    @Test
    void shareIsProportionalToLiquidity() {
        // 1000 emission, position owns 1/4 of in-range liquidity → 250
        assertThat(LpFarmingService.positionShare(1_000, 2_500, 10_000)).isEqualTo(250L);
    }

    @Test
    void shareFloorsDustDown() {
        // 10 emission * 1 / 3 = 3.33 → floor 3
        assertThat(LpFarmingService.positionShare(10, 1, 3)).isEqualTo(3L);
    }

    @Test
    void soleInRangePositionTakesWholeEmission() {
        assertThat(LpFarmingService.positionShare(1_000, 5_000, 5_000)).isEqualTo(1_000L);
        // even if its share somehow exceeds the bucket total, it's capped at emission
        assertThat(LpFarmingService.positionShare(1_000, 9_999, 5_000)).isEqualTo(1_000L);
    }

    @Test
    void shareIsZeroForNonPositiveInputs() {
        assertThat(LpFarmingService.positionShare(0, 100, 100)).isZero();
        assertThat(LpFarmingService.positionShare(1_000, 0, 100)).isZero();
        assertThat(LpFarmingService.positionShare(1_000, 100, 0)).isZero();
        assertThat(LpFarmingService.positionShare(-5, 100, 100)).isZero();
    }

    @Test
    void shareIsOverflowSafeForHugeAmounts() {
        // emission * liquidity overflows a long (9.2e18 * 9.2e18); BigInteger keeps it exact.
        // position owns exactly half → expect emission / 2.
        long emission = Long.MAX_VALUE;           // 9_223_372_036_854_775_807
        long total = Long.MAX_VALUE;
        long half = total / 2;                     // 4_611_686_018_427_387_903
        assertThat(LpFarmingService.positionShare(emission, half, total))
                .isEqualTo(BigIntegerHalf(emission, half, total));
        // and a plain long product would have overflowed — sanity check the helper
        // returns a value strictly less than the full emission for a half stake.
        assertThat(LpFarmingService.positionShare(emission, half, total)).isLessThan(emission);
        assertThat(LpFarmingService.positionShare(emission, half, total)).isGreaterThan(emission / 2 - 2);
    }

    private static long BigIntegerHalf(long emission, long pos, long total) {
        return java.math.BigInteger.valueOf(emission)
                .multiply(java.math.BigInteger.valueOf(pos))
                .divide(java.math.BigInteger.valueOf(total))
                .longValue();
    }

    // ── accruePosition ───────────────────────────────────────────────────────

    @Test
    void accrueCreatesRowOnFirstAccrual() {
        when(rewardRepository.findByPositionId(POSITION)).thenReturn(Optional.empty());
        when(rewardRepository.save(any(PoolPositionReward.class))).thenAnswer(inv -> inv.getArgument(0));

        service.accruePosition(POSITION, POOL, USER, REWARD_TOKEN, 250);

        ArgumentCaptor<PoolPositionReward> saved = ArgumentCaptor.forClass(PoolPositionReward.class);
        verify(rewardRepository).save(saved.capture());
        PoolPositionReward row = saved.getValue();
        assertThat(row.getPositionId()).isEqualTo(POSITION);
        assertThat(row.getPoolId()).isEqualTo(POOL);
        assertThat(row.getUserId()).isEqualTo(USER);
        assertThat(row.getRewardTokenId()).isEqualTo(REWARD_TOKEN);
        assertThat(row.getUnclaimedReward()).isEqualTo(250L);
        assertThat(row.getClaimedReward()).isZero();
        assertThat(row.getLastAccrualAt()).isNotNull();
    }

    @Test
    void accrueAddsToExistingRow() {
        PoolPositionReward existing = PoolPositionReward.builder()
                .id(UUID.randomUUID()).positionId(POSITION).poolId(POOL).userId(USER)
                .rewardTokenId(REWARD_TOKEN).unclaimedReward(100L).claimedReward(40L).build();
        when(rewardRepository.findByPositionId(POSITION)).thenReturn(Optional.of(existing));
        when(rewardRepository.save(any(PoolPositionReward.class))).thenAnswer(inv -> inv.getArgument(0));

        service.accruePosition(POSITION, POOL, USER, REWARD_TOKEN, 75);

        verify(rewardRepository).save(existing);
        assertThat(existing.getUnclaimedReward()).isEqualTo(175L); // 100 + 75
        assertThat(existing.getClaimedReward()).isEqualTo(40L);    // untouched
    }

    @Test
    void accrueIsNoOpForZeroShare() {
        service.accruePosition(POSITION, POOL, USER, REWARD_TOKEN, 0);
        verify(rewardRepository, never()).findByPositionId(any());
        verify(rewardRepository, never()).save(any());
    }

    // ── claim ────────────────────────────────────────────────────────────────

    @Test
    void claimSumsZeroesAndCreditsViaBalanceWriter() {
        PoolPositionReward a = PoolPositionReward.builder()
                .id(UUID.randomUUID()).positionId(UUID.randomUUID()).poolId(POOL).userId(USER)
                .rewardTokenId(REWARD_TOKEN).unclaimedReward(300L).claimedReward(0L).build();
        PoolPositionReward b = PoolPositionReward.builder()
                .id(UUID.randomUUID()).positionId(UUID.randomUUID()).poolId(POOL).userId(USER)
                .rewardTokenId(REWARD_TOKEN).unclaimedReward(700L).claimedReward(50L).build();
        when(rewardRepository.findByUserIdAndUnclaimedRewardGreaterThan(USER, 0L))
                .thenReturn(List.of(a, b));

        long total = service.claim(USER);

        assertThat(total).isEqualTo(1_000L);                 // 300 + 700
        verify(balanceWriter).credit(USER, REWARD_TOKEN, 1_000L);
        verify(rewardRepository).saveAll(List.of(a, b));
        // zeroed + moved to claimed
        assertThat(a.getUnclaimedReward()).isZero();
        assertThat(a.getClaimedReward()).isEqualTo(300L);
        assertThat(b.getUnclaimedReward()).isZero();
        assertThat(b.getClaimedReward()).isEqualTo(750L);    // 50 + 700
    }

    @Test
    void claimWithNothingPendingCreditsNothing() {
        when(rewardRepository.findByUserIdAndUnclaimedRewardGreaterThan(USER, 0L))
                .thenReturn(List.of());

        long total = service.claim(USER);

        assertThat(total).isZero();
        verify(balanceWriter, never()).credit(any(), any(), anyLong());
        verify(rewardRepository, never()).saveAll(any());
    }

    // ── getUserSummary ─────────────────────────────────────────────────────────

    @Test
    void summaryAggregatesPerPoolAndTotals() {
        UUID poolB = UUID.randomUUID();
        PoolPositionReward p1 = PoolPositionReward.builder()
                .id(UUID.randomUUID()).positionId(UUID.randomUUID()).poolId(POOL).userId(USER)
                .rewardTokenId(REWARD_TOKEN).unclaimedReward(200L).claimedReward(10L).build();
        PoolPositionReward p2 = PoolPositionReward.builder()
                .id(UUID.randomUUID()).positionId(UUID.randomUUID()).poolId(POOL).userId(USER)
                .rewardTokenId(REWARD_TOKEN).unclaimedReward(300L).claimedReward(0L).build();
        PoolPositionReward p3 = PoolPositionReward.builder()
                .id(UUID.randomUUID()).positionId(UUID.randomUUID()).poolId(poolB).userId(USER)
                .rewardTokenId(REWARD_TOKEN).unclaimedReward(50L).claimedReward(5L).build();
        when(rewardRepository.findByUserId(USER)).thenReturn(List.of(p1, p2, p3));

        FarmRewardSummary summary = service.getUserSummary(USER);

        assertThat(summary.totalUnclaimed()).isEqualTo(550L); // 200 + 300 + 50
        assertThat(summary.totalClaimed()).isEqualTo(15L);    // 10 + 0 + 5
        assertThat(summary.pools()).hasSize(2);
        FarmRewardSummary.PoolReward poolA = summary.pools().stream()
                .filter(pr -> pr.poolId().equals(POOL)).findFirst().orElseThrow();
        assertThat(poolA.unclaimed()).isEqualTo(500L);        // 200 + 300 merged
        assertThat(poolA.claimed()).isEqualTo(10L);
    }
}
