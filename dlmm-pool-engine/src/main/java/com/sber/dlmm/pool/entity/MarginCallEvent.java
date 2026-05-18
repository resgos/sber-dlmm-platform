package com.sber.dlmm.pool.entity;

import com.sber.dlmm.common.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 4 #4.3 — audit row emitted by {@code MarginWatchService} each
 * time an LP position crosses (or approaches) a margin-call threshold.
 *
 * <p>Reuses {@link NotificationType} for the {@code eventType} so the
 * downstream notification topic can route on it directly. Only the two
 * MARGIN_* values are persisted here; the column type is wide enough
 * that we won't need a migration if we add INTERMEDIATE_WARNING later.
 */
@Entity
@Table(name = "margin_call_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarginCallEvent {

    @Id
    private UUID id;

    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private NotificationType eventType;

    @Column(name = "active_bin_id", nullable = false)
    private int activeBinId;

    @Column(name = "range_min", nullable = false)
    private int rangeMin;

    @Column(name = "range_max", nullable = false)
    private int rangeMax;

    /**
     * Signed bin-distance from the nearest range boundary. Negative =
     * outside (magnitude = how many bins out). Positive = inside (magnitude
     * = bins of safety margin). Storing the sign saves a re-derivation
     * for "trending toward margin call" queries.
     */
    @Column(name = "distance_from_boundary", nullable = false)
    private int distanceFromBoundary;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
