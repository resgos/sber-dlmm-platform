package com.sber.dlmm.pool.event;

import java.util.UUID;

/**
 * Domain event published to the {@code pool-events} Kafka topic (via the
 * transactional outbox) when a user cancels an open limit order and the
 * escrowed input is refunded to their balance.
 *
 * <p>Consumers (notably notification-service) use it to confirm the
 * cancellation and refund to the user and to update downstream read models,
 * without re-querying pool-engine.
 *
 * @param poolId         the pool the cancelled order targeted
 * @param userId         the trader who cancelled the order (refund recipient)
 * @param limitOrderId   id of the cancelled limit order
 * @param tokenInId      the escrowed token refunded back to the user
 * @param amountRefunded raw amount refunded from escrow (1 unit = 10⁻⁴ token)
 */
public record LimitOrderCancelledEvent(
        UUID poolId,
        UUID userId,
        UUID limitOrderId,
        UUID tokenInId,
        long amountRefunded
) {
}
