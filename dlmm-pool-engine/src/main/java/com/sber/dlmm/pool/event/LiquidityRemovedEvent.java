package com.sber.dlmm.pool.event;

import java.util.UUID;

/**
 * Domain event published to the {@code pool-events} Kafka topic (via the
 * transactional outbox) when a user removes liquidity from one of their LP
 * positions and the proportional token amounts (plus accrued fees) are
 * returned.
 *
 * <p>Consumers (notably notification-service) use it to render the user's
 * "liquidity removed" alert and to update downstream read models without
 * re-querying pool-engine, since the event already carries the affected
 * pool, owner, position, and the withdrawn token amounts.
 *
 * @param poolId     the pool the liquidity was withdrawn from
 * @param userId     the LP who removed the liquidity (position owner)
 * @param positionId the position that was reduced or closed
 * @param amountX    raw amount of token X returned (1 unit = 10⁻⁴ token)
 * @param amountY    raw amount of token Y returned (1 unit = 10⁻⁴ token)
 */
public record LiquidityRemovedEvent(
        UUID poolId,
        UUID userId,
        UUID positionId,
        long amountX,
        long amountY
) {
}
