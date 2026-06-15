package com.sber.dlmm.notification.repository;

import com.sber.dlmm.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Notification} entities.
 *
 * <p>All lookups are deliberately scoped by {@code userId} so a user can only ever read or
 * mutate their own notifications (the API derives the user id from the authenticated JWT,
 * never from a client-supplied parameter). Inherits the standard CRUD operations from
 * {@link JpaRepository}; the methods below add the user-scoped queries the service needs.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Returns one page of a user's notifications (all read states), ordered by the supplied
     * {@link Pageable} (the service sorts newest-first).
     *
     * @param userId   owner whose notifications to fetch
     * @param pageable page index, size and sort
     * @return a page of the user's notifications
     */
    Page<Notification> findByUserId(UUID userId, Pageable pageable);

    /**
     * Returns one page of a user's <em>unread</em> notifications only.
     *
     * <p>Backs the {@code unreadOnly=true} listing path. Derived from the {@code read} flag
     * being {@code false}.
     *
     * @param userId   owner whose unread notifications to fetch
     * @param pageable page index, size and sort
     * @return a page of the user's unread notifications
     */
    Page<Notification> findByUserIdAndReadFalse(UUID userId, Pageable pageable);

    /**
     * Looks up a single notification by id, but only if it belongs to the given user.
     *
     * <p>The {@code userId} predicate is an ownership guard: a request for someone else's
     * notification yields an empty {@link Optional} (treated as 404) rather than leaking it.
     *
     * @param id     notification identifier
     * @param userId expected owner
     * @return the notification if it exists and is owned by {@code userId}, otherwise empty
     */
    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Counts a user's unread notifications (for the badge counter).
     *
     * @param userId owner whose unread notifications to count
     * @return number of unread notifications for the user
     */
    long countByUserIdAndReadFalse(UUID userId);

    /**
     * Counts a user's unread notifications grouped by category. Each row is
     * {@code [type, count]}; the service collects them into a per-category map for a
     * categorised badge ("3 margin alerts, 2 fee accruals"). Categories with no unread
     * notifications are absent.
     *
     * <p>Deliberately a <b>native</b> query that returns the type column as a raw
     * {@code String}, NOT the {@link com.sber.dlmm.common.enums.NotificationType} enum:
     * some legacy/seeded rows carry type values no longer in the enum
     * (e.g. {@code SWAP_CONFIRMED}, {@code LIQUIDITY_ADDED}), and a JPQL projection of
     * {@code n.type} would blow up materialising those into the enum. Strings make the
     * tally resilient to enum drift and still serialise straight to the client.
     *
     * @param userId owner whose unread notifications to tally
     * @return list of {@code [String type, Number count]} rows
     */
    @Query(value = "SELECT type, COUNT(*) FROM notifications WHERE user_id = :userId AND is_read = false GROUP BY type",
            nativeQuery = true)
    List<Object[]> countUnreadByType(@Param("userId") UUID userId);

    /**
     * Bulk-marks all of a user's unread notifications as read in a single UPDATE.
     *
     * <p>Used by the "mark all as read" action. Sets {@code read=true} and stamps {@code readAt}
     * with the database {@code CURRENT_TIMESTAMP}; only rows currently unread are touched, so a
     * repeated call is effectively idempotent (updates zero rows). Requires {@code @Modifying}
     * and an enclosing transaction.
     *
     * @param userId owner whose unread notifications to mark read
     * @return the number of rows updated (notifications newly marked read)
     */
    @Modifying
    @Query("UPDATE Notification n SET n.read = true, n.readAt = CURRENT_TIMESTAMP WHERE n.userId = :userId AND n.read = false")
    int markAllAsReadByUserId(@Param("userId") UUID userId);
}
