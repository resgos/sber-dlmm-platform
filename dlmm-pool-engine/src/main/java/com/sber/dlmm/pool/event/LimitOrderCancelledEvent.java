package com.sber.dlmm.pool.event;

import java.util.UUID;

/** Emitted when a user cancels an open limit order (escrow refunded). */
public record LimitOrderCancelledEvent(
        UUID poolId,
        UUID userId,
        UUID limitOrderId,
        UUID tokenInId,
        long amountRefunded
) {
}
