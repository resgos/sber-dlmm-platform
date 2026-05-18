package com.sber.dlmm.pool.repository;

import com.sber.dlmm.common.enums.NotificationType;
import com.sber.dlmm.pool.entity.MarginCallEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

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
     */
    Optional<MarginCallEvent> findTopByPositionIdAndEventTypeOrderByCreatedAtDesc(
            UUID positionId, NotificationType eventType);

    /**
     * Listing for the user-ui "Risk alerts" tab (Sprint 5+). Bounded query —
     * caller passes a {@code since} cutoff so we don't accidentally load
     * a year of history.
     */
    long countByUserIdAndCreatedAtAfter(UUID userId, LocalDateTime since);
}
