package com.sber.dlmm.pool.event;

import java.util.UUID;

public record LiquidityRemovedEvent(
        UUID poolId,
        UUID userId,
        UUID positionId,
        long amountX,
        long amountY
) {
}
