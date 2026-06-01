package com.sber.dlmm.user.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain event published to the {@code user-events} Kafka topic when a user's
 * KYC status transitions to {@code VERIFIED} (see
 * {@code UserService.updateKycStatus}). Only the VERIFIED transition is
 * broadcast — other KYC states do not emit this event. Lets downstream
 * services unlock KYC-gated features (trading, withdrawals) reactively.
 *
 * <p>Immutable carrier record; the producer keys the Kafka message by
 * {@code userId} for per-user partition ordering.
 *
 * @param userId     id of the user whose KYC was verified
 * @param verifiedAt server-side timestamp at which verification was recorded
 */
public record UserKycVerifiedEvent(
        UUID userId,
        LocalDateTime verifiedAt
) {
}
