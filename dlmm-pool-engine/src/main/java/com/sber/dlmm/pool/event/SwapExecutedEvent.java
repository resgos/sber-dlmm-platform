package com.sber.dlmm.pool.event;

import java.util.UUID;

public record SwapExecutedEvent(
        UUID poolId,
        UUID userId,
        UUID tokenInId,
        long amountIn,
        long amountOut,
        long fee,
        int binsCrossed
) {
}
