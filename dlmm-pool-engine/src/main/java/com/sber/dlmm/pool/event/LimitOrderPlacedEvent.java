package com.sber.dlmm.pool.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Domain event published to the {@code pool-events} Kafka topic (via the
 * transactional outbox) when a user places a limit order and the input
 * amount is taken into escrow.
 *
 * <p>Consumers (notably notification-service) use it to confirm the order
 * was accepted and to seed downstream read models, without re-querying
 * pool-engine for the order's pool, owner, side, size, or target price.
 *
 * @param poolId       the pool the limit order targets
 * @param userId       the trader who placed the order (escrow owner)
 * @param limitOrderId id of the placed limit order
 * @param side         order side — {@code SELL} escrows token X (pays Y), {@code BUY} escrows token Y (pays X)
 * @param amountIn     raw input amount taken into escrow (1 unit = 10⁻⁴ token)
 * @param limitPrice   target Y-per-X price at which the order should fill
 */
public record LimitOrderPlacedEvent(
        UUID poolId,
        UUID userId,
        UUID limitOrderId,
        String side,
        long amountIn,
        BigDecimal limitPrice
) {
}
