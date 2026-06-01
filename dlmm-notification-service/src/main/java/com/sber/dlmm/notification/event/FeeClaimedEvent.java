package com.sber.dlmm.notification.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Event consumed from the {@code fee-events} topic when an LP claims accrued fees.
 *
 * <p>Local mirror of the event published (via the outbox) by the fee-service; the listener
 * deserializes the {@code fee-events} payload and crafts a {@code FEE_ACCRUED} notification.
 * Defined here as a typed contract/documentation — in practice the listener reads the JSON
 * field-by-field so an extra producer-side field never breaks consumption. Amounts are raw
 * integers in platform scale (1 unit = 10⁻⁴ token), not human-readable decimals.
 *
 * @param positionId identifier of the LP position the fees were claimed from
 * @param userId     identifier of the user who claimed (the notification recipient)
 * @param poolId     identifier of the pool the position belongs to
 * @param claimedX   amount of token X claimed, in raw platform units
 * @param claimedY   amount of token Y claimed, in raw platform units
 * @param tokenXId   identifier of the pool's X token
 * @param tokenYId   identifier of the pool's Y token
 * @param claimedAt  server timestamp when the claim was executed
 */
public record FeeClaimedEvent(
        UUID positionId,
        UUID userId,
        UUID poolId,
        long claimedX,
        long claimedY,
        UUID tokenXId,
        UUID tokenYId,
        LocalDateTime claimedAt
) {}
