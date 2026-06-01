package com.sber.dlmm.token.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event emitted when a token is transferred between two users.
 *
 * <p>Published by {@code TokenService.transfer} through the transactional
 * outbox to the {@code token-events} Kafka topic (event type
 * {@code "TokenTransferred"}) once the transfer transaction commits.
 * Consumers — notably notification-service — use it to notify sender and/or
 * recipient; audit consumers reconcile movements and can use
 * {@code idempotencyKey} to dedupe replays.
 *
 * <p>{@code amount} is a raw integer quantity on the uniform ×10⁴ platform scale.
 *
 * @param tokenId        token that was moved
 * @param from           user debited (transfer source)
 * @param to             user credited (transfer destination)
 * @param amount         quantity transferred, raw ×10⁴
 * @param idempotencyKey client-supplied dedup key carried from the request, or null when none was provided
 * @param at             moment the transfer occurred
 */
public record TokenTransferredEvent(
        UUID tokenId,
        UUID from,
        UUID to,
        long amount,
        String idempotencyKey,
        LocalDateTime at
) {
}
