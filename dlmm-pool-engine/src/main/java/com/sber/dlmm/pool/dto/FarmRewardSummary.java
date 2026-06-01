package com.sber.dlmm.pool.dto;

import java.util.List;
import java.util.UUID;

/**
 * Sprint 17 — a user's LP-farming reward summary: the total still claimable plus
 * a per-pool breakdown. Returned by the farming rewards endpoint. Amounts are raw
 * (×10⁴) reward-token units — the user-ui divides by AMOUNT_SCALE at the boundary.
 *
 * @param totalUnclaimed total reward still claimable across all pools, raw integer at
 *                       10⁻⁴ scale
 * @param totalClaimed   total reward already claimed historically, raw integer at
 *                       10⁻⁴ scale
 * @param pools          per-pool breakdown of the user's accrued rewards
 */
public record FarmRewardSummary(
        long totalUnclaimed,
        long totalClaimed,
        List<PoolReward> pools
) {
    /**
     * One pool's accrued reward for the user (summed over their positions in it).
     *
     * @param poolId        the pool the reward was earned in
     * @param rewardTokenId id of the token the reward is paid in
     * @param unclaimed     amount still claimable in this pool, raw integer at 10⁻⁴ scale
     * @param claimed       amount already claimed from this pool, raw integer at 10⁻⁴ scale
     */
    public record PoolReward(
            UUID poolId,
            UUID rewardTokenId,
            long unclaimed,
            long claimed
    ) {}
}
