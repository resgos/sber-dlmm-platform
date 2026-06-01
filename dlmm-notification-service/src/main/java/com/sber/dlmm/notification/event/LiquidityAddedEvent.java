package com.sber.dlmm.notification.event;

import java.util.UUID;

/**
 * Event consumed from the {@code pool-events} topic when a user adds liquidity to a pool.
 *
 * <p>Local mirror of the event published (via the outbox) by the pool-engine. The listener
 * recognizes this shape (a {@code positionId} plus {@code amountX}/{@code amountY} and
 * <em>no</em> {@code fee} field — see {@code determinePoolEventType}) and emits a
 * notification confirming the deposit. Amounts are raw integers in platform scale
 * (1 unit = 10⁻⁴ token).
 *
 * @param poolId     identifier of the pool liquidity was added to
 * @param userId     identifier of the user who added liquidity (the notification recipient)
 * @param positionId identifier of the LP position created or topped up
 * @param amountX    amount of token X deposited, in raw platform units
 * @param amountY    amount of token Y deposited, in raw platform units
 */
public record LiquidityAddedEvent(
        UUID poolId,
        UUID userId,
        UUID positionId,
        long amountX,
        long amountY
) {}
