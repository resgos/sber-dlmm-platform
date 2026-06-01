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
 *
 * @param txId            id of the swap transaction (used as the ledger row id by transaction-service)
 * @param poolId          the pool the swap ran against
 * @param userId          the trader who executed the swap
 * @param tokenInId       token paid in by the trader
 * @param tokenOutId      token received by the trader
 * @param amountIn        raw amount paid in (1 unit = 10⁻⁴ token)
 * @param amountOut       raw amount received out (1 unit = 10⁻⁴ token)
 * @param fee             raw fee charged on the swap (1 unit = 10⁻⁴ token)
 * @param binsCrossed     number of price bins the swap traversed
 * @param idempotencyKey  caller-supplied idempotency key; the user-ui open-hedges query filters on the {@code hedge-} prefix
 * @param executionPrice  normalized Y-per-X execution price as a plain string; required by the price-oracle OHLCV consumer
 * @param timestamp       execution time in epoch millis, so OHLCV candles are stamped with the swap time even on replay
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
