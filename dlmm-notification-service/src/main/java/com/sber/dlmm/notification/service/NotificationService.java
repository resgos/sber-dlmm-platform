package com.sber.dlmm.notification.service;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.enums.NotificationType;
import com.sber.dlmm.notification.dto.NotificationResponse;
import com.sber.dlmm.notification.entity.Notification;
import com.sber.dlmm.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Application service encapsulating all notification business logic.
 *
 * <p>Sits between the two entry points — the Kafka {@link com.sber.dlmm.notification.listener.NotificationEventListener}
 * (write path: persist a notification crafted from a domain event) and the REST
 * {@link com.sber.dlmm.notification.controller.NotificationController} (read/mutate path: list,
 * count, mark read) — and the {@link NotificationRepository}. It owns transaction boundaries
 * and the entity→{@link NotificationResponse} mapping. The constructor and logger are generated
 * by Lombok ({@code @RequiredArgsConstructor}/{@code @Slf4j}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    /** Data-access gateway for the {@code notifications} table. */
    private final NotificationRepository notificationRepository;

    /**
     * Persists a new notification for a user (the write path invoked by the Kafka listener).
     *
     * <p>Builds the entity unread with the current timestamp and saves it. The {@code payload}
     * is the original raw event JSON, retained for client-side context/deep-linking.
     *
     * @param userId  recipient the notification is created for
     * @param type    notification category driving client rendering
     * @param title   short headline (already localized by the caller)
     * @param message body text (already localized by the caller)
     * @param payload original raw event JSON, or {@code null}
     * @return the persisted {@link Notification} (with its generated id populated)
     */
    @Transactional
    public Notification createNotification(UUID userId, NotificationType type,
                                            String title, String message, String payload) {
        Notification notification = Notification.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .message(message)
                .payload(payload)
                .read(false)
                .createdAt(LocalDateTime.now())
                .build();

        Notification saved = notificationRepository.save(notification);
        log.info("Created notification id={} type={} userId={}", saved.getId(), type, userId);
        return saved;
    }

    /**
     * Returns a page of a user's notifications, newest first, optionally restricted to unread.
     *
     * <p>Sorts by {@code createdAt} descending so the freshest notifications surface at the top
     * of the panel, then maps the entity page into the transport-friendly {@link PageResponse}
     * of {@link NotificationResponse}. Runs read-only.
     *
     * @param userId     owner whose notifications to list
     * @param unreadOnly when {@code true}, return only unread notifications; otherwise all
     * @param page       zero-based page index
     * @param size       page size (notifications per page)
     * @return a paged response of mapped notifications plus paging metadata
     */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> getUserNotifications(UUID userId,
                                                                     boolean unreadOnly,
                                                                     int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Notification> notificationPage;
        if (unreadOnly) {
            notificationPage = notificationRepository.findByUserIdAndReadFalse(userId, pageRequest);
        } else {
            notificationPage = notificationRepository.findByUserId(userId, pageRequest);
        }

        return new PageResponse<>(
                notificationPage.getContent().stream().map(this::toResponse).toList(),
                notificationPage.getNumber(),
                notificationPage.getSize(),
                notificationPage.getTotalElements(),
                notificationPage.getTotalPages()
        );
    }

    /**
     * Marks a single notification as read, enforcing that it belongs to the requesting user.
     *
     * <p>Loads the notification scoped by both id and {@code userId}; a missing/foreign id
     * throws (surfaced as 404). The update is a no-op when the notification is already read,
     * making repeated clicks idempotent and avoiding a redundant write/timestamp churn.
     *
     * @param notificationId id of the notification to mark read
     * @param userId         owner making the request (ownership guard)
     * @throws RuntimeException if no notification with that id exists for the user
     */
    @Transactional
    public void markAsRead(UUID notificationId, UUID userId) {
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new RuntimeException("Notification not found: " + notificationId));

        if (!notification.isRead()) {
            notification.setRead(true);
            notification.setReadAt(LocalDateTime.now());
            notificationRepository.save(notification);
            log.debug("Marked notification {} as read for user {}", notificationId, userId);
        }
    }

    /**
     * Marks every unread notification of a user as read in one bulk UPDATE.
     *
     * <p>Delegates to the bulk repository query (a single statement rather than load-then-save
     * per row) and logs how many rows were affected.
     *
     * @param userId owner whose notifications to mark read
     * @param type   when non-null, only this category is cleared (lets a user clear a noisy
     *               category, e.g. dozens of MARGIN_WARNINGs); when null, every category
     */
    @Transactional
    public void markAllAsRead(UUID userId, NotificationType type) {
        int updated = (type == null)
                ? notificationRepository.markAllAsReadByUserId(userId)
                : notificationRepository.markAllAsReadByUserIdAndType(userId, type);
        log.info("Marked {} notifications as read for user {} (type={})", updated, userId, type);
    }

    /**
     * Returns the number of unread notifications for a user (for the badge counter). Read-only.
     *
     * @param userId owner whose unread notifications to count
     * @return count of unread notifications
     */
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    /**
     * Returns the number of unread notifications for a user grouped by category name, for a
     * categorised badge (e.g. "3 margin alerts, 2 fee accruals"). Categories with no unread
     * notifications are absent; keys are the raw {@code type} strings (resilient to enum
     * drift — see {@link com.sber.dlmm.notification.repository.NotificationRepository#countUnreadByType}).
     * Ordered alphabetically for a stable response. Read-only.
     *
     * @param userId owner whose unread notifications to tally
     * @return map of category name to its unread count (only non-zero entries)
     */
    @Transactional(readOnly = true)
    public Map<String, Long> getUnreadCountByType(UUID userId) {
        Map<String, Long> counts = new TreeMap<>();
        for (Object[] row : notificationRepository.countUnreadByType(userId)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * Maps a persisted {@link Notification} entity to its API {@link NotificationResponse} DTO.
     *
     * <p>Single point of entity→DTO translation so the API shape stays decoupled from the
     * persistence model.
     *
     * @param n the entity to convert
     * @return the equivalent response DTO
     */
    private NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getUserId(),
                n.getType(),
                n.getTitle(),
                n.getMessage(),
                n.getPayload(),
                n.isRead(),
                n.getCreatedAt(),
                n.getReadAt()
        );
    }
}
