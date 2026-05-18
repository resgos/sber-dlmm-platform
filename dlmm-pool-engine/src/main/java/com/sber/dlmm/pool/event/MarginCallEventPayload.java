package com.sber.dlmm.pool.event;

import com.sber.dlmm.common.enums.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 4 #4.3 — payload published to the notification topic when
 * {@code MarginWatchService} fires a margin event. notification-service
 * consumes this and renders the user-facing alert.
 *
 * <p>Carries the precise state so the consumer doesn't need to re-query
 * pool-engine to render the message — "your position in pool X has
 * drifted N bins outside its range".
 */
public record MarginCallEventPayload(
        UUID eventId,
        UUID positionId,
        UUID userId,
        UUID poolId,
        NotificationType eventType,
        int activeBinId,
        int rangeMin,
        int rangeMax,
        int distanceFromBoundary,
        LocalDateTime emittedAt
) {
}
