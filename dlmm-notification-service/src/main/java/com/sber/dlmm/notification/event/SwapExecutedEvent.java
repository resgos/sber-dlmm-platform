package com.sber.dlmm.notification.event;

import java.util.UUID;

public record SwapExecutedEvent(
        UUID poolId,
        UUID userId,
        UUID tokenInId,
        long amountIn,
        long amountOut,
        long fee,
        int binsCrossed
) {}
