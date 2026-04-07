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
        boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime closedAt,
        List<BinAllocation> binAllocations
) {
}
