package com.sber.dlmm.user.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event published to the {@code user-events} Kafka topic immediately
 * after a successful registration (see {@code UserService.register}). Lets
 * downstream services (notification-service, analytics) react to a new
 * account without coupling to user-service's database.
 *
 * <p>Immutable carrier record. Deliberately carries only non-sensitive
 * identity fields — never the password hash or KYC internals. The producer
 * keys the Kafka message by {@code userId} for per-user partition ordering.
 *
 * @param userId    the newly assigned internal user id (primary key)
 * @param sberId    the user's Sber identifier (business key, unique)
 * @param email     the user's email address (also unique at registration)
 * @param createdAt server-side creation timestamp of the user row
 */
public record UserCreatedEvent(
        UUID userId,
        String sberId,
        String email,
        LocalDateTime createdAt
) {
}
