package com.sber.dlmm.user.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event published to the {@code user-events} Kafka topic when an admin
 * blocks a user (see {@code UserService.blockUser}). Consumed downstream
 * (e.g. notification-service) to react to the user being disabled — the block
 * itself is applied synchronously in user-service; this event is the
 * after-the-fact broadcast.
 *
 * <p>Immutable carrier record; the producer keys the Kafka message by
 * {@code userId} so a single user's lifecycle events stay ordered on one
 * partition.
 *
 * @param userId    id of the user that was blocked
 * @param blockedAt server-side timestamp at which the block was applied
 */
public record UserBlockedEvent(
        UUID userId,
        LocalDateTime blockedAt
) {
}
