package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.calendar.BankingCalendarService;
import com.sber.dlmm.common.enums.NotificationType;
import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.entity.LpPosition;
import com.sber.dlmm.pool.entity.MarginCallEvent;
import com.sber.dlmm.pool.event.MarginCallEventPayload;
import com.sber.dlmm.pool.repository.MarginCallEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Sprint 4 #4.3 — per-position margin-call evaluation and event emission.
 *
 * <p>Split from {@link MarginWatchScheduler} so that {@code @Transactional}
 * fires through the Spring AOP proxy boundary on each per-position call
 * (the scheduler iterates pages and invokes us once per position).
 *
 * <p>Decision logic lives in {@link #decideEvent} as a pure static
 * function — easy to unit-test without spinning up JPA + outbox mocks,
 * and surfaces the rule for risk-committee review on a single screen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarginWatchService {

    private final MarginCallEventRepository eventRepository;
    private final OutboxService outbox;
    /**
     * Sprint 5 #5.10 — used to compute working-day-aware rebalance
     * deadline in the emitted event payload. Margin-call scheduler
     * itself is NOT gated by the calendar (risk feature must fire 24/7
     * regardless of bank holidays) — only the SLA deadline is
     * calendar-aware.
     */
    private final BankingCalendarService bankingCalendar;

    /** Notification topic — matches the existing notification-service consumer. */
    private static final String NOTIFICATION_TOPIC = "user-events";

    /**
     * Sprint 5 #5.10 — how many working days the treasurer has to
     * rebalance an out-of-range position. Default T+2 (Russian
     * banking-conventional settlement window). Configurable per
     * environment.
     */
    @Value("${dlmm.margin.rebalance-working-days:2}")
    private int rebalanceWorkingDays;

    /**
     * Pure decision function — returns the event type to emit, or empty
     * if the position is comfortably in-range and no alert is warranted.
     *
     * <ul>
     *   <li>active_bin OUTSIDE [rangeMin..rangeMax] → {@link NotificationType#MARGIN_CALL}.
     *   <li>active_bin within {@code warningDistance} bins of either boundary
     *       (but still inside) → {@link NotificationType#MARGIN_WARNING}.
     *   <li>otherwise → empty (no event).
     * </ul>
     *
     * <p>{@code distanceFromBoundary} returned alongside is signed:
     * negative = bins OUTSIDE the nearest boundary (magnitude = how far out),
     * positive = bins INSIDE (magnitude = safety margin).
     *
     * @param activeBin       the pool's current active bin id
     * @param rangeMin        inclusive lower bin of the position's range
     * @param rangeMax        inclusive upper bin of the position's range
     * @param warningDistance bins-from-boundary at/under which a still-in-range
     *                        position earns a {@code MARGIN_WARNING}
     * @return the event to emit (empty = no alert) plus the signed distance
     */
    public static EvaluationResult decideEvent(int activeBin, int rangeMin, int rangeMax,
                                                int warningDistance) {
        if (rangeMin > rangeMax) {
            // Defensive — bad position data, don't alert. Logged in caller.
            return new EvaluationResult(Optional.empty(), 0);
        }
        if (activeBin < rangeMin) {
            int distance = activeBin - rangeMin; // negative
            return new EvaluationResult(Optional.of(NotificationType.MARGIN_CALL), distance);
        }
        if (activeBin > rangeMax) {
            int distance = rangeMax - activeBin; // negative
            return new EvaluationResult(Optional.of(NotificationType.MARGIN_CALL), distance);
        }
        // Inside the range — compute safety margin to the nearer boundary.
        int distanceToLower = activeBin - rangeMin;       // ≥ 0
        int distanceToUpper = rangeMax - activeBin;       // ≥ 0
        int safetyMargin = Math.min(distanceToLower, distanceToUpper);
        if (safetyMargin <= warningDistance) {
            return new EvaluationResult(Optional.of(NotificationType.MARGIN_WARNING), safetyMargin);
        }
        return new EvaluationResult(Optional.empty(), safetyMargin);
    }

    /**
     * Per-position evaluation, idempotent within a cooldown window. Called
     * by the scheduler. Each call commits in its own short transaction so
     * one bad position can't roll back a sweep.
     *
     * <p>Runs {@link #decideEvent}; if an alert is warranted and the same alert
     * type hasn't fired for this position within {@code cooldown} (distinct
     * types fire independently — a CALL after a WARNING is not suppressed), it
     * persists a {@link MarginCallEvent} and appends a notification to the
     * outbox (so the alert survives a notification-service/Kafka outage). The
     * payload's rebalance deadline is computed via the banking calendar so the
     * treasurer gets a working-day-aware date.
     *
     * @param position        the LP position to evaluate
     * @param pool            its pool (supplies the current active bin)
     * @param warningDistance bins-from-boundary threshold for a warning
     * @param cooldown        dedup window suppressing a repeat of the same event type
     * @return true if an event was emitted, false if skipped (no trigger
     *         or cooldown active).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean evaluatePosition(LpPosition position, LiquidityPool pool,
                                     int warningDistance, Duration cooldown) {
        EvaluationResult result = decideEvent(
                pool.getActiveBinId(),
                position.getBinRangeMin(),
                position.getBinRangeMax(),
                warningDistance);

        if (result.eventType().isEmpty()) {
            return false;
        }
        NotificationType eventType = result.eventType().get();

        // Cooldown dedup — don't re-fire same alert type for same position
        // within the cooldown window. Distinct types fire independently
        // (a MARGIN_CALL after a MARGIN_WARNING isn't suppressed).
        Optional<MarginCallEvent> last = eventRepository
                .findTopByPositionIdAndEventTypeOrderByCreatedAtDesc(position.getId(), eventType);
        if (last.isPresent()) {
            LocalDateTime cutoff = LocalDateTime.now().minus(cooldown);
            if (last.get().getCreatedAt().isAfter(cutoff)) {
                log.debug("Skipping {} for position {} — last fired {} (within cooldown)",
                        eventType, position.getId(), last.get().getCreatedAt());
                return false;
            }
        }

        MarginCallEvent event = MarginCallEvent.builder()
                .positionId(position.getId())
                .userId(position.getUserId())
                .poolId(position.getPoolId())
                .eventType(eventType)
                .activeBinId(pool.getActiveBinId())
                .rangeMin(position.getBinRangeMin())
                .rangeMax(position.getBinRangeMax())
                .distanceFromBoundary(result.distance())
                .build();
        eventRepository.save(event);

        // Publish to notification topic via outbox so the alert survives
        // a notification-service or Kafka outage. Payload carries the
        // bits notification-service needs to render the message.
        //
        // Sprint 5 #5.10 — rebalanceDeadline computed via banking calendar
        // so treasurer sees "к понедельнику" (working-day aware), not a
        // weekend that they can't act on.
        java.time.LocalDate rebalanceDeadline = bankingCalendar.addWorkingDays(
                event.getCreatedAt().toLocalDate(), rebalanceWorkingDays);
        outbox.append("margin-call", event.getId().toString(), eventType.name(),
                NOTIFICATION_TOPIC,
                new MarginCallEventPayload(
                        event.getId(),
                        position.getId(),
                        position.getUserId(),
                        position.getPoolId(),
                        eventType,
                        pool.getActiveBinId(),
                        position.getBinRangeMin(),
                        position.getBinRangeMax(),
                        result.distance(),
                        event.getCreatedAt(),
                        rebalanceDeadline));

        log.info("Margin event {} emitted for position={} user={} pool={} activeBin={} range=[{},{}] distance={}",
                eventType, position.getId(), position.getUserId(), position.getPoolId(),
                pool.getActiveBinId(), position.getBinRangeMin(), position.getBinRangeMax(),
                result.distance());
        return true;
    }

    /**
     * Result of {@link #decideEvent}. Empty {@code eventType} = no alert.
     * {@code distance} is signed: negative = bins outside boundary,
     * positive = bins of safety margin inside.
     */
    public record EvaluationResult(Optional<NotificationType> eventType, int distance) {}
}
