package com.sber.dlmm.notification.event;

import java.util.UUID;

public record LiquidityAddedEvent(
        UUID poolId,
        UUID userId,
        UUID positionId,
        long amountX,
        long amountY
) {}
