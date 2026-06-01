package com.sber.dlmm.notification.event;

import java.util.UUID;

/**
 * Event consumed from the {@code pool-events} topic when a swap is executed against a pool.
 *
 * <p>Local mirror of the event published (via the outbox) by the pool-engine's swap flow.
 * The listener recognizes this shape by the presence of {@code tokenInId} + {@code amountIn}
 * + {@code fee} (see {@code determinePoolEventType}) and emits a {@code SWAP_COMPLETED}
 * notification. Amounts/fee are raw integers in platform scale (1 unit = 10⁻⁴ token).
 *
 * @param poolId       identifier of the pool the swap ran against
 * @param userId       identifier of the user who swapped (the notification recipient)
 * @param tokenInId    identifier of the input token the user paid
 * @param amountIn     input amount paid, in raw platform units
 * @param amountOut    output amount received, in raw platform units
 * @param fee          fee charged on the swap, in raw platform units
 * @param binsCrossed  number of price bins the swap traversed (a measure of price impact)
 */
public record SwapExecutedEvent(
        UUID poolId,
        UUID userId,
        UUID tokenInId,
        long amountIn,
        long amountOut,
        long fee,
        int binsCrossed
) {}
