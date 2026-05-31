package com.sber.dlmm.pool.event;

import java.math.BigDecimal;
import java.util.UUID;

/** Emitted when a limit order fills (output credited at the limit price). */
public record LimitOrderFilledEvent(
        UUID poolId,
        UUID userId,
        UUID limitOrderId,
        UUID tokenOutId,
        long amountOut,
        BigDecimal fillPrice
) {
}
