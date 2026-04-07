package com.sber.dlmm.pool.event;

import java.util.UUID;

public record LiquidityAddedEvent(
        UUID poolId,
        UUID userId,
        UUID positionId,
        long amountX,
        long amountY
) {
}
