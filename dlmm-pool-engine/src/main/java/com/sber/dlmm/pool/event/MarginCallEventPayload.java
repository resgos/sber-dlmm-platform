package com.sber.dlmm.pool.event;

import com.sber.dlmm.common.enums.NotificationType;

import java.time.LocalDate;
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
 *
 * <p>Sprint 5 #5.10 — added {@code rebalanceDeadline}: working-day-aware
 * deadline for the treasurer to act (computed via {@code BankingCalendarService}
 * with {@code dlmm.margin.rebalance-working-days}, default T+2). For
 * MARGIN_WARNING this is informational; for MARGIN_CALL it's an SLA target
 * for risk-committee post-mortem. Null tolerated for back-compat with
 * pre-5.10 stored events.
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
        LocalDateTime emittedAt,
        LocalDate rebalanceDeadline
) {
}
