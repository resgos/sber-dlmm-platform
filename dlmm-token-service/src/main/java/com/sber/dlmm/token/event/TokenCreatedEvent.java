package com.sber.dlmm.token.event;

import com.sber.dlmm.common.enums.TokenType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event emitted when a new token is registered in the catalog.
 *
 * <p>Published by {@code TokenService.createToken} through the transactional
 * outbox to the {@code token-events} Kafka topic (event type
 * {@code "TokenCreated"}), so it is delivered exactly once the creating
 * transaction commits. Consumers — notably notification-service — react to
 * it (e.g. to notify admins/listings that a new token went live); it also
 * feeds any analytics/audit pipeline subscribed to the topic.
 *
 * @param tokenId   id of the newly created token
 * @param symbol    ticker symbol of the token (e.g. {@code SBTC})
 * @param type      classification of the token — see {@link TokenType}
 * @param createdBy id of the admin who created the token
 * @param at        moment the token was created
 */
public record TokenCreatedEvent(
        UUID tokenId,
        String symbol,
        TokenType type,
        UUID createdBy,
        LocalDateTime at
) {
}
