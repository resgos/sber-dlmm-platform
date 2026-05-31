package com.sber.dlmm.pool.event;

import java.math.BigDecimal;
import java.util.UUID;

/** Emitted when a user places a limit order (escrow taken). */
public record LimitOrderPlacedEvent(
        UUID poolId,
        UUID userId,
        UUID limitOrderId,
        String side,
        long amountIn,
        BigDecimal limitPrice
) {
}
