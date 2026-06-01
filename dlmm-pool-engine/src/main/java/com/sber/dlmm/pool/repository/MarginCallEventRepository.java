package com.sber.dlmm.pool.repository;

import com.sber.dlmm.common.enums.NotificationType;
import com.sber.dlmm.pool.entity.MarginCallEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link MarginCallEvent} — the audit/alert trail written when
 * an LP position approaches or leaves its bin range. Used by
 * {@code MarginWatchService} for cooldown de-duplication and by the user-ui
 * risk-alerts view.
 */
@Repository
public interface MarginCallEventRepository extends JpaRepository<MarginCallEvent, UUID> {

    /**
     * Sprint 4 #4.3 — last event of the given type for a position. Used by
     * {@code MarginWatchService} to dedupe within a cooldown window so we
     * don't flood the same treasurer with WARNING every 5 minutes when
     * a position is sitting just at the boundary.
     *
     * <p>Spring Data derived query — {@code findTopBy...OrderByCreatedAtDesc}
     * is portable across Hibernate 6.x without needing JPQL LIMIT support.
     *
     * @param positionId position to look up
     * @param eventType  event severity to match (MARGIN_WARNING / MARGIN_CALL)
     * @return the most recent matching event, or empty if none yet
     */
    Optional<MarginCallEvent> findTopByPositionIdAndEventTypeOrderByCreatedAtDesc(
            UUID positionId, NotificationType eventType);

    /**
     * Listing for the user-ui "Risk alerts" tab (Sprint 5+). Bounded query —
     * caller passes a {@code since} cutoff so we don't accidentally load
     * a year of history.
     *
     * @param userId user whose alerts to count
     * @param since  exclusive lower bound on {@code createdAt}
     * @return number of the user's events strictly after {@code since}
     */
    long countByUserIdAndCreatedAtAfter(UUID userId, LocalDateTime since);
}
