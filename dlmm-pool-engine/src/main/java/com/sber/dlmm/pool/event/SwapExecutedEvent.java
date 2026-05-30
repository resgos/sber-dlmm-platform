package com.sber.dlmm.pool.event;

import java.util.UUID;

/**
 * Sprint 9-DS-r2 — extended with txId + tokenOutId + idempotencyKey so
 * the transaction-service Kafka consumer can persist a row that the
 * /transactions/me feed (and the user-ui open-hedges query, which
 * filters by `idempotencyKey.startsWith('hedge-')`) can find.
 *
 * <p>Previously the event carried only the in-side, so every live
 * swap left a balance delta but no transaction record — both
 * /transactions/me and "Открытые хеджи" silently stayed empty.
 */
public record SwapExecutedEvent(
        UUID txId,
        UUID poolId,
        UUID userId,
        UUID tokenInId,
        UUID tokenOutId,
        long amountIn,
        long amountOut,
        long fee,
        int binsCrossed,
        String idempotencyKey,
        // Sprint 10 (OHLCV fix) — normalized Y-per-X execution price
        // (BigDecimal.toPlainString) and execution time (epoch millis).
        // Without these the price-oracle OHLCV consumer dropped every swap
        // (its bare-payload gate requires executionPrice) and, on replay,
        // stamped candles with its own wall clock. Appended at the end so
        // positional construction elsewhere stays stable.
        String executionPrice,
        long timestamp
) {
}
