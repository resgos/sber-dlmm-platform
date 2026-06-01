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
 *
 * @param eventId               unique id of this margin event (consumer dedup key)
 * @param positionId            the LP position whose range is at risk
 * @param userId                owner of the position (alert recipient)
 * @param poolId                the pool the position belongs to
 * @param eventType             margin severity — MARGIN_WARNING (informational) or MARGIN_CALL (action required)
 * @param activeBinId           the pool's current active bin at emission time
 * @param rangeMin              lower bound (inclusive) of the position's bin range
 * @param rangeMax              upper bound (inclusive) of the position's bin range
 * @param distanceFromBoundary  how many bins the active bin has drifted past the nearest range edge
 * @param emittedAt             when the margin event was produced
 * @param rebalanceDeadline     working-day-aware deadline (T+N) for the treasurer to rebalance; null for pre-5.10 events
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
