package com.sber.dlmm.token.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event emitted when token supply is burned from a user.
 *
 * <p>Published by {@code TokenService.burn} through the transactional outbox
 * to the {@code token-events} Kafka topic (event type {@code "TokenBurned"})
 * once the burning transaction commits. Consumers — notably
 * notification-service — use it to inform the user of the debit;
 * analytics/audit consumers track supply changes via {@code newTotalSupply}.
 *
 * <p>Amounts are raw integer quantities on the uniform ×10⁴ platform scale.
 *
 * @param tokenId        token whose supply was reduced
 * @param fromUserId     user the amount was burned from
 * @param amount         quantity burned, raw ×10⁴
 * @param newTotalSupply token's total supply after this burn, raw ×10⁴
 * @param at             moment the burn occurred
 */
public record TokenBurnedEvent(
        UUID tokenId,
        UUID fromUserId,
        long amount,
        long newTotalSupply,
        LocalDateTime at
) {
}
