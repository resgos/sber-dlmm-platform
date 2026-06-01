package com.sber.dlmm.token.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event emitted when token supply is minted to a user.
 *
 * <p>Published by {@code TokenService.mint} through the transactional outbox
 * to the {@code token-events} Kafka topic (event type {@code "TokenMinted"})
 * once the minting transaction commits. Consumers — notably
 * notification-service — use it to inform the recipient of the credit;
 * analytics/audit consumers track supply changes via {@code newTotalSupply}.
 *
 * <p>Amounts are raw integer quantities on the uniform ×10⁴ platform scale.
 *
 * @param tokenId        token whose supply was increased
 * @param toUserId       user credited with the minted amount
 * @param amount         quantity minted, raw ×10⁴
 * @param newTotalSupply token's total supply after this mint, raw ×10⁴
 * @param at             moment the mint occurred
 */
public record TokenMintedEvent(
        UUID tokenId,
        UUID toUserId,
        long amount,
        long newTotalSupply,
        LocalDateTime at
) {
}
