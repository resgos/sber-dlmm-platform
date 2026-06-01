package com.sber.dlmm.pool.event;

import java.util.UUID;

/**
 * Domain event published to the {@code pool-events} Kafka topic (via the
 * transactional outbox) when a user adds liquidity to a pool and an LP
 * position is opened or topped up.
 *
 * <p>Consumers (notably notification-service) use it to render the user's
 * "liquidity added" alert and to update downstream read models without
 * re-querying pool-engine, since the event already carries the affected
 * pool, owner, position, and the deposited token amounts.
 *
 * @param poolId     the pool that received the liquidity
 * @param userId     the LP who added the liquidity (position owner)
 * @param positionId the position that was opened or topped up
 * @param amountX    raw amount of token X deposited (1 unit = 10⁻⁴ token)
 * @param amountY    raw amount of token Y deposited (1 unit = 10⁻⁴ token)
 */
public record LiquidityAddedEvent(
        UUID poolId,
        UUID userId,
        UUID positionId,
        long amountX,
        long amountY
) {
}
