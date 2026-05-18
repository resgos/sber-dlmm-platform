package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.calendar.BankingCalendarService;
import com.sber.dlmm.common.enums.NotificationType;
import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.entity.LpPosition;
import com.sber.dlmm.pool.entity.MarginCallEvent;
import com.sber.dlmm.pool.repository.MarginCallEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarginWatchServiceTest {

    @Mock
    private MarginCallEventRepository eventRepository;
    @Mock
    private OutboxService outbox;
    // Sprint 5 #5.10 — real BankingCalendarService (stateless, no I/O) so
    // the rebalanceDeadline computation is exercised end-to-end.
    @Spy
    private BankingCalendarService bankingCalendar = new BankingCalendarService();

    @InjectMocks
    private MarginWatchService service;

    private static final UUID POOL_ID = UUID.randomUUID();
    private static final UUID POSITION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final int WARNING_DISTANCE = 3;
    private static final Duration COOLDOWN = Duration.ofHours(6);

    private LiquidityPool pool;
    private LpPosition position;

    @BeforeEach
    void setUp() {
        // Position covers bin range [100, 200] — gives plenty of room
        // for boundary-distance assertions without arithmetic surprises.
        position = LpPosition.builder()
                .id(POSITION_ID)
                .userId(USER_ID)
                .poolId(POOL_ID)
                .binRangeMin(100)
                .binRangeMax(200)
                .isActive(true)
                .build();
        pool = LiquidityPool.builder()
                .id(POOL_ID)
                .basePrice(BigDecimal.ONE)
                .status(PoolStatus.ACTIVE)
                .build();
        // Mirror what JPA @PrePersist does in prod — assign id + createdAt
        // before returning the saved entity. Without this, the service's
        // outbox.append(... event.getId().toString() ...) NPEs in tests.
        org.mockito.Mockito.lenient()
                .when(eventRepository.save(org.mockito.ArgumentMatchers.any(MarginCallEvent.class)))
                .thenAnswer(inv -> {
                    MarginCallEvent e = inv.getArgument(0);
                    if (e.getId() == null) e.setId(UUID.randomUUID());
                    if (e.getCreatedAt() == null) e.setCreatedAt(LocalDateTime.now());
                    return e;
                });
    }

    /**
     * Pure decision-function unit tests — no Spring, no JPA, no mocks.
     */
    @Nested
    @DisplayName("decideEvent — pure logic")
    class DecideEventTests {

        @Test
        @DisplayName("deeply in-range position returns no event with safety margin")
        void inRangeNoEvent() {
            // active=150, range=[100,200] → 50 bins of safety either side
            var r = MarginWatchService.decideEvent(150, 100, 200, WARNING_DISTANCE);
            assertTrue(r.eventType().isEmpty());
            assertEquals(50, r.distance()); // positive = safety margin
        }

        @Test
        @DisplayName("active bin below rangeMin → MARGIN_CALL with negative distance")
        void belowRangeIsMarginCall() {
            // active=95 vs rangeMin=100 → 5 bins outside the lower edge
            var r = MarginWatchService.decideEvent(95, 100, 200, WARNING_DISTANCE);
            assertEquals(Optional.of(NotificationType.MARGIN_CALL), r.eventType());
            assertEquals(-5, r.distance());
        }

        @Test
        @DisplayName("active bin above rangeMax → MARGIN_CALL with negative distance")
        void aboveRangeIsMarginCall() {
            // active=210 vs rangeMax=200 → 10 bins outside the upper edge
            var r = MarginWatchService.decideEvent(210, 100, 200, WARNING_DISTANCE);
            assertEquals(Optional.of(NotificationType.MARGIN_CALL), r.eventType());
            assertEquals(-10, r.distance());
        }

        @Test
        @DisplayName("within warning distance of lower boundary → MARGIN_WARNING")
        void nearLowerBoundaryIsWarning() {
            // active=102 vs rangeMin=100 → 2 bins of safety = within warning (≤3)
            var r = MarginWatchService.decideEvent(102, 100, 200, WARNING_DISTANCE);
            assertEquals(Optional.of(NotificationType.MARGIN_WARNING), r.eventType());
            assertEquals(2, r.distance());
        }

        @Test
        @DisplayName("within warning distance of upper boundary → MARGIN_WARNING")
        void nearUpperBoundaryIsWarning() {
            // active=198 vs rangeMax=200 → 2 bins of safety
            var r = MarginWatchService.decideEvent(198, 100, 200, WARNING_DISTANCE);
            assertEquals(Optional.of(NotificationType.MARGIN_WARNING), r.eventType());
            assertEquals(2, r.distance());
        }

        @Test
        @DisplayName("exactly at boundary fires WARNING, not CALL — still inside the range")
        void atBoundaryIsWarning() {
            // active=100 == rangeMin → still inside, safety=0, within warning
            var r = MarginWatchService.decideEvent(100, 100, 200, WARNING_DISTANCE);
            assertEquals(Optional.of(NotificationType.MARGIN_WARNING), r.eventType());
            assertEquals(0, r.distance());
        }

        @Test
        @DisplayName("malformed range (min > max) is defensively skipped, no event")
        void malformedRangeNoEvent() {
            var r = MarginWatchService.decideEvent(150, 200, 100, WARNING_DISTANCE);
            assertTrue(r.eventType().isEmpty());
            assertEquals(0, r.distance());
        }

        @Test
        @DisplayName("warning distance 0 disables WARNING (only MARGIN_CALL ever fires)")
        void warningDistanceZeroSilencesWarning() {
            // active=101 → safety=1, but warningDistance=0 → no event
            var r = MarginWatchService.decideEvent(101, 100, 200, 0);
            assertTrue(r.eventType().isEmpty());
            assertEquals(1, r.distance());
        }
    }

    /**
     * evaluatePosition — verifies persistence + outbox emission + cooldown dedup.
     */
    @Nested
    @DisplayName("evaluatePosition — orchestration")
    class EvaluatePositionTests {

        @Test
        @DisplayName("MARGIN_CALL: persists event row and publishes to outbox")
        void marginCallPersistsAndPublishes() {
            pool.setActiveBinId(50); // outside [100, 200] → MARGIN_CALL
            when(eventRepository.findTopByPositionIdAndEventTypeOrderByCreatedAtDesc(
                    POSITION_ID, NotificationType.MARGIN_CALL)).thenReturn(Optional.empty());

            boolean fired = service.evaluatePosition(position, pool, WARNING_DISTANCE, COOLDOWN);

            assertTrue(fired);
            ArgumentCaptor<MarginCallEvent> captor = ArgumentCaptor.forClass(MarginCallEvent.class);
            verify(eventRepository).save(captor.capture());
            MarginCallEvent saved = captor.getValue();
            assertEquals(NotificationType.MARGIN_CALL, saved.getEventType());
            assertEquals(POSITION_ID, saved.getPositionId());
            assertEquals(USER_ID, saved.getUserId());
            assertEquals(POOL_ID, saved.getPoolId());
            assertEquals(50, saved.getActiveBinId());
            assertEquals(-50, saved.getDistanceFromBoundary()); // 50 - 100

            verify(outbox).append(eq("margin-call"), anyString(), eq("MARGIN_CALL"),
                    eq("user-events"), any());
        }

        @Test
        @DisplayName("MARGIN_WARNING: same persistence + outbox flow as MARGIN_CALL")
        void marginWarningEmits() {
            pool.setActiveBinId(102); // safety=2, within warningDistance=3 → WARNING
            when(eventRepository.findTopByPositionIdAndEventTypeOrderByCreatedAtDesc(
                    POSITION_ID, NotificationType.MARGIN_WARNING)).thenReturn(Optional.empty());

            boolean fired = service.evaluatePosition(position, pool, WARNING_DISTANCE, COOLDOWN);

            assertTrue(fired);
            ArgumentCaptor<MarginCallEvent> captor = ArgumentCaptor.forClass(MarginCallEvent.class);
            verify(eventRepository).save(captor.capture());
            assertEquals(NotificationType.MARGIN_WARNING, captor.getValue().getEventType());
            assertEquals(2, captor.getValue().getDistanceFromBoundary());
            verify(outbox).append(eq("margin-call"), anyString(), eq("MARGIN_WARNING"),
                    eq("user-events"), any());
        }

        @Test
        @DisplayName("In-range position: no save, no outbox, returns false")
        void inRangeSkips() {
            pool.setActiveBinId(150); // deep inside [100, 200]

            boolean fired = service.evaluatePosition(position, pool, WARNING_DISTANCE, COOLDOWN);

            assertFalse(fired);
            verify(eventRepository, never()).save(any());
            verify(outbox, never()).append(anyString(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Cooldown: dedupes repeat alerts within window")
        void cooldownDedupes() {
            pool.setActiveBinId(50); // would fire MARGIN_CALL
            MarginCallEvent last = MarginCallEvent.builder()
                    .id(UUID.randomUUID())
                    .eventType(NotificationType.MARGIN_CALL)
                    .createdAt(LocalDateTime.now().minusHours(2)) // 2h ago, cooldown=6h
                    .build();
            when(eventRepository.findTopByPositionIdAndEventTypeOrderByCreatedAtDesc(
                    POSITION_ID, NotificationType.MARGIN_CALL)).thenReturn(Optional.of(last));

            boolean fired = service.evaluatePosition(position, pool, WARNING_DISTANCE, COOLDOWN);

            assertFalse(fired);
            verify(eventRepository, never()).save(any());
            verify(outbox, never()).append(anyString(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Cooldown expired: re-fires the alert and persists fresh row")
        void cooldownExpiredRefires() {
            pool.setActiveBinId(50);
            MarginCallEvent last = MarginCallEvent.builder()
                    .id(UUID.randomUUID())
                    .eventType(NotificationType.MARGIN_CALL)
                    .createdAt(LocalDateTime.now().minusHours(8)) // 8h ago > 6h cooldown
                    .build();
            when(eventRepository.findTopByPositionIdAndEventTypeOrderByCreatedAtDesc(
                    POSITION_ID, NotificationType.MARGIN_CALL)).thenReturn(Optional.of(last));

            boolean fired = service.evaluatePosition(position, pool, WARNING_DISTANCE, COOLDOWN);

            assertTrue(fired);
            verify(eventRepository).save(any());
            verify(outbox).append(anyString(), anyString(), eq("MARGIN_CALL"),
                    anyString(), any());
        }

        @Test
        @DisplayName("WARNING cooldown doesn't block a fresh MARGIN_CALL — distinct types are independent")
        void warningCooldownDoesNotBlockMarginCall() {
            pool.setActiveBinId(50); // fires MARGIN_CALL
            // No prior MARGIN_CALL event — only an old WARNING. Service checks
            // for MARGIN_CALL specifically (not "any margin event") so this should still fire.
            when(eventRepository.findTopByPositionIdAndEventTypeOrderByCreatedAtDesc(
                    POSITION_ID, NotificationType.MARGIN_CALL)).thenReturn(Optional.empty());

            boolean fired = service.evaluatePosition(position, pool, WARNING_DISTANCE, COOLDOWN);

            assertTrue(fired);
            verify(eventRepository).save(any());
        }
    }
}
