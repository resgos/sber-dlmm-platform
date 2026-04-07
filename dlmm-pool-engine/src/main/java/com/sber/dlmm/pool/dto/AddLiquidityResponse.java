package com.sber.dlmm.pool.dto;

import com.sber.dlmm.common.enums.LiquidityStrategy;

import java.util.List;
import java.util.UUID;

public record AddLiquidityResponse(
        UUID positionId,
        UUID poolId,
        int binRangeMin,
        int binRangeMax,
        LiquidityStrategy strategy,
        long depositedX,
        long depositedY,
        long liquidityShares,
        List<BinAllocation> binAllocations
) {
}
