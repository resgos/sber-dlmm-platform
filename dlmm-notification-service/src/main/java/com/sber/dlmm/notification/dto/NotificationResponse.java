package com.sber.dlmm.notification.dto;

import com.sber.dlmm.common.enums.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * API representation of a single notification returned to the user-ui.
 *
 * <p>Flat read-model projection of the {@link com.sber.dlmm.notification.entity.Notification}
 * entity, mapped by {@code NotificationService#toResponse}. It is serialized directly to JSON
 * by the controller; the {@code payload} field carries the original raw event JSON so the
 * frontend can deep-link or render extra context if needed.
 *
 * @param id        unique identifier of the notification
 * @param userId    identifier of the user the notification belongs to (its owner)
 * @param type      category of the notification (e.g. SWAP_COMPLETED, KYC_APPROVED, MARGIN_CALL)
 *                  driving icon/wording on the client
 * @param title     short, already-localized headline shown in the notification panel
 * @param message   already-localized body text describing what happened
 * @param payload   original raw event JSON the notification was derived from (may be {@code null}),
 *                  available for client-side deep-linking/context
 * @param read      whether the user has already read this notification
 * @param createdAt server timestamp when the notification was created
 * @param readAt    server timestamp when it was marked read, or {@code null} while still unread
 */
public record NotificationResponse(
        UUID id,
        UUID userId,
        NotificationType type,
        String title,
        String message,
        String payload,
        boolean read,
        LocalDateTime createdAt,
        LocalDateTime readAt
) {}
