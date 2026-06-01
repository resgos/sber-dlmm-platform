package com.sber.dlmm.pool.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event published to the {@code pool-events} Kafka topic (via the
 * transactional outbox) when an administrator creates a new DLMM liquidity
 * pool for a token pair at a given bin step.
 *
 * <p>Consumers use it to learn that a new tradable pool now exists — e.g.
 * notification-service for announcements and downstream read models that
 * track the pool catalog — without polling pool-engine for new pools.
 *
 * @param poolId   the newly created pool
 * @param tokenXId the base token (X side) of the pair
 * @param tokenYId the quote token (Y side) of the pair
 * @param binStep  the pool's bin step in basis points (price granularity)
 * @param at       the creation timestamp
 */
public record PoolCreatedEvent(
        UUID poolId,
        UUID tokenXId,
        UUID tokenYId,
        int binStep,
        LocalDateTime at
) {
}
