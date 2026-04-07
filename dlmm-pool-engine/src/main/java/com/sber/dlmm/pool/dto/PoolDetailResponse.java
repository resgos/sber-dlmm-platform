package com.sber.dlmm.pool.dto;

import java.util.List;

public record PoolDetailResponse(
        PoolResponse pool,
        List<BinResponse> bins,
        int volatilityAccumulator,
        int currentDynamicFeeBps,
        long totalFeesCollectedX,
        long totalFeesCollectedY
) {
}
