package com.sber.dlmm.notification.event;

import java.util.UUID;

/**
 * Event consumed from the {@code user-events} topic when a user's KYC verification is approved.
 *
 * <p>Local mirror of the event published (via the outbox) by the user-service. The listener
 * recognizes this shape by the presence of {@code userId} + {@code email} (see
 * {@code handleUserEvents}) and emits a {@code KYC_APPROVED} notification welcoming the user
 * and confirming full platform access.
 *
 * @param userId   identifier of the verified user (the notification recipient)
 * @param email    the user's email address (used to distinguish this event shape; not displayed)
 * @param fullName the user's full name, interpolated into the greeting (defaults to "User" if absent)
 */
public record UserKycVerifiedEvent(
        UUID userId,
        String email,
        String fullName
) {}
