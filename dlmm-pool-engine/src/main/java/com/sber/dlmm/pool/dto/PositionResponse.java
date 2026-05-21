package com.sber.dlmm.pool.dto;

import com.sber.dlmm.common.enums.LiquidityStrategy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PositionResponse(
        UUID id,
        UUID userId,
        UUID poolId,
        int binRangeMin,
        int binRangeMax,
        LiquidityStrategy strategy,
        long totalLiquidityShares,
        long currentValueX,
        long currentValueY,
        long unclaimedFeeX,
        long unclaimedFeeY,
        // Sprint 9-DS-r4 (P1-10) — cost-basis for the PositionsPage
        // P&L column. Sum of all deposits to this position, scaled
        // down proportionally on partial removes. 0 for legacy
        // positions opened before the schema migration.
        long initialDepositX,
        long initialDepositY,
        boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime closedAt,
        List<BinAllocation> binAllocations
) {
}
