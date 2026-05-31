package com.sber.dlmm.pool.dto;

import java.util.List;
import java.util.UUID;

/**
 * Sprint 17 — a user's LP-farming reward summary: the total still claimable plus
 * a per-pool breakdown. Amounts are raw (×10⁴) reward-token units — the user-ui
 * divides by AMOUNT_SCALE at the boundary.
 */
public record FarmRewardSummary(
        long totalUnclaimed,
        long totalClaimed,
        List<PoolReward> pools
) {
    /** One pool's accrued reward for the user (summed over their positions in it). */
    public record PoolReward(
            UUID poolId,
            UUID rewardTokenId,
            long unclaimed,
            long claimed
    ) {}
}
