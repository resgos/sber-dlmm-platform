package com.sber.dlmm.pool.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Domain event published to the {@code pool-events} Kafka topic (via the
 * transactional outbox) when a limit order fills — the background watcher
 * crosses the target price and credits the output token to the owner.
 *
 * <p>Consumers (notably notification-service) use it to alert the user that
 * their order executed and to record the fill in downstream read models
 * (e.g. transaction-service), without re-querying pool-engine.
 *
 * @param poolId       the pool the order filled against
 * @param userId       the trader who owned the order (credit recipient)
 * @param limitOrderId id of the limit order that filled
 * @param tokenOutId   token credited to the user on fill
 * @param amountOut    raw output amount credited (1 unit = 10⁻⁴ token)
 * @param fillPrice    the Y-per-X price at which the order filled (the limit price)
 */
public record LimitOrderFilledEvent(
        UUID poolId,
        UUID userId,
        UUID limitOrderId,
        UUID tokenOutId,
        long amountOut,
        BigDecimal fillPrice
) {
}
