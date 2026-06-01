package com.sber.dlmm.pool.service;

import com.sber.dlmm.pool.dto.FarmRewardSummary;
import com.sber.dlmm.pool.entity.PoolPositionReward;
import com.sber.dlmm.pool.repository.PoolPositionRewardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sprint 17 — LP-farming rewards engine. Reward token is Spasibo (SSPAS).
 *
 * <p>Model: each {@code pool_rewards_config} row emits a fixed daily amount of
 * the reward token; the {@link LpFarmingScheduler} pro-rates that to each
 * accrual cycle and, for every <b>in-range</b> active position in the pool,
 * calls {@link #accruePosition} with the position's share of the cycle's
 * emission. A position is in-range (eligible) when the pool's {@code activeBinId}
 * lies within {@code [binRangeMin, binRangeMax]}; the split is proportional to
 * each position's {@code totalLiquidityShares} over the in-range total — exactly
 * a single-bucket version of the classic MasterChef "reward per share" accrual.
 *
 * <p>{@link #claim} settles the user's accrued reward to their balance. Both the
 * accrual (scheduler thread, no JWT) and the claim (request thread) credit via
 * {@link LimitOrderBalanceWriter} — a direct UPSERT into {@code user_balances}
 * inside this transaction, the same mechanism limit-order fills use.
 *
 * <p>All reward amounts are raw platform units (uniform ×10⁴ scale).
 */
@Service
public class LpFarmingService {

    private static final Logger log = LoggerFactory.getLogger(LpFarmingService.class);

    private final PoolPositionRewardRepository rewardRepository;
    private final LimitOrderBalanceWriter balanceWriter;

    /**
     * @param rewardRepository per-position reward rows (accrual + claim)
     * @param balanceWriter    in-transaction credit of accrual and claim payouts
     */
    public LpFarmingService(PoolPositionRewardRepository rewardRepository,
                            LimitOrderBalanceWriter balanceWriter) {
        this.rewardRepository = rewardRepository;
        this.balanceWriter = balanceWriter;
    }

    /**
     * The reward a single position earns from one emission slice:
     * {@code floor(emission × positionLiquidity / totalInRangeLiquidity)}.
     *
     * <p>Computed with {@link BigInteger} so {@code emission × positionLiquidity}
     * can't overflow a {@code long} before the divide (both are raw ×10⁴ amounts
     * and can be large). Returns 0 for any non-positive input or empty bucket —
     * dust below one raw unit is dropped (floor), which keeps the sum of shares
     * ≤ emission so a pool can never over-distribute.
     *
     * @param emissionPerDay        emission slice to split (raw units; name is
     *                              historical — the scheduler passes the already
     *                              pro-rated cycle emission)
     * @param positionLiquidity     this position's in-range liquidity shares
     * @param totalInRangeLiquidity sum of all in-range positions' shares
     * @return floored reward for this position (0 for any non-positive input;
     *         the whole emission when it is the sole in-range LP)
     */
    public static long positionShare(long emissionPerDay, long positionLiquidity, long totalInRangeLiquidity) {
        if (emissionPerDay <= 0 || positionLiquidity <= 0 || totalInRangeLiquidity <= 0) return 0L;
        if (positionLiquidity >= totalInRangeLiquidity) return emissionPerDay; // sole/whole in-range LP
        return BigInteger.valueOf(emissionPerDay)
                .multiply(BigInteger.valueOf(positionLiquidity))
                .divide(BigInteger.valueOf(totalInRangeLiquidity))
                .longValue();
    }

    /**
     * Add {@code share} raw reward units to a position's reward row, creating the
     * row on first accrual. Runs in its OWN transaction (separate bean from the
     * scheduler sweep) so one position's failure doesn't roll back the others.
     *
     * @param positionId    position earning the reward
     * @param poolId        the position's pool (stamped on a new row)
     * @param userId        the position's owner (stamped on a new row)
     * @param rewardTokenId reward token (stamped on a new row)
     * @param share         raw reward units to add (≤ 0 → no-op)
     */
    @Transactional
    public void accruePosition(UUID positionId, UUID poolId, UUID userId, UUID rewardTokenId, long share) {
        if (share <= 0) return;
        PoolPositionReward row = rewardRepository.findByPositionId(positionId).orElse(null);
        if (row == null) {
            row = PoolPositionReward.builder()
                    .positionId(positionId)
                    .poolId(poolId)
                    .userId(userId)
                    .rewardTokenId(rewardTokenId)
                    .unclaimedReward(0L)
                    .claimedReward(0L)
                    .build();
        }
        row.setUnclaimedReward(row.getUnclaimedReward() + share);
        row.setLastAccrualAt(LocalDateTime.now());
        rewardRepository.save(row);
    }

    /**
     * Build a user's reward summary: their total unclaimed + claimed reward and a
     * per-pool breakdown (one entry per pool the user has a reward row in, summed
     * over their positions). Read-only.
     *
     * @param userId user to summarise
     * @return totals plus a per-pool reward breakdown
     */
    @Transactional(readOnly = true)
    public FarmRewardSummary getUserSummary(UUID userId) {
        List<PoolPositionReward> rows = rewardRepository.findByUserId(userId);
        Map<UUID, FarmRewardSummary.PoolReward> byPool = new LinkedHashMap<>();
        long totalUnclaimed = 0L;
        long totalClaimed = 0L;
        for (PoolPositionReward row : rows) {
            totalUnclaimed += row.getUnclaimedReward();
            totalClaimed += row.getClaimedReward();
            UUID poolId = row.getPoolId();
            FarmRewardSummary.PoolReward agg = byPool.get(poolId);
            long unclaimed = (agg == null ? 0L : agg.unclaimed()) + row.getUnclaimedReward();
            long claimed = (agg == null ? 0L : agg.claimed()) + row.getClaimedReward();
            byPool.put(poolId, new FarmRewardSummary.PoolReward(
                    poolId, row.getRewardTokenId(), unclaimed, claimed));
        }
        return new FarmRewardSummary(totalUnclaimed, totalClaimed, new ArrayList<>(byPool.values()));
    }

    /**
     * Settle all of {@code userId}'s accrued reward: sum {@code unclaimedReward}
     * across their rows, zero each, move the amount into {@code claimedReward},
     * and credit the reward token to the user's balance. Returns the total
     * credited (0 if nothing was pending). Idempotent under repeat calls: once
     * zeroed there is nothing left to claim, so a double-tap credits nothing.
     *
     * @param userId user claiming their accrued rewards
     * @return total raw reward units credited (0 if nothing was pending)
     */
    @Transactional
    public long claim(UUID userId) {
        List<PoolPositionReward> rows = rewardRepository.findByUserIdAndUnclaimedRewardGreaterThan(userId, 0L);
        long total = 0L;
        UUID rewardTokenId = null;
        for (PoolPositionReward row : rows) {
            long pending = row.getUnclaimedReward();
            if (pending <= 0) continue;
            total += pending;
            rewardTokenId = row.getRewardTokenId();
            row.setUnclaimedReward(0L);
            row.setClaimedReward(row.getClaimedReward() + pending);
        }
        if (total <= 0 || rewardTokenId == null) return 0L;
        rewardRepository.saveAll(rows);
        balanceWriter.credit(userId, rewardTokenId, total);
        log.info("LP-farming claim: credited {} raw reward units to user {}", total, userId);
        return total;
    }
}
