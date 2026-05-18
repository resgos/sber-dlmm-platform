package com.sber.dlmm.user.service;

import com.sber.dlmm.user.entity.UserSelfRestriction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 6 #6.7 — pure state-machine tests on
 * {@link SelfRestrictionService#isActiveAt}. No Spring, no JPA.
 */
class SelfRestrictionStateTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 3, 12, 0);

    private UserSelfRestriction row(UserSelfRestriction.Action action,
                                     LocalDateTime created, LocalDateTime effective) {
        return UserSelfRestriction.builder()
                .id(UUID.randomUUID())
                .userId(USER)
                .action(action)
                .scope(UserSelfRestriction.Scope.ALL_NEW_POSITIONS)
                .createdAt(created)
                .effectiveAt(effective)
                .build();
    }

    @Test
    @DisplayName("empty history → not active")
    void emptyHistory() {
        assertFalse(SelfRestrictionService.isActiveAt(List.of(), NOW));
    }

    @Test
    @DisplayName("only SET → active")
    void onlySet() {
        assertTrue(SelfRestrictionService.isActiveAt(
                List.of(row(UserSelfRestriction.Action.SET, NOW.minusDays(1), NOW.minusDays(1))),
                NOW));
    }

    @Test
    @DisplayName("SET → LIFTED past effective_at → not active")
    void liftCompleted() {
        var history = List.of(
                row(UserSelfRestriction.Action.SET, NOW.minusDays(10), NOW.minusDays(10)),
                row(UserSelfRestriction.Action.LIFTED, NOW.minusDays(1), NOW.minusDays(1))
        );
        assertFalse(SelfRestrictionService.isActiveAt(history, NOW));
    }

    @Test
    @DisplayName("SET → LIFT_REQUESTED only → STILL active (cooling not elapsed; no LIFTED row yet)")
    void liftRequestedKeepsActive() {
        // LIFT_REQUESTED with effective_at in the future = cooling still on
        var history = List.of(
                row(UserSelfRestriction.Action.SET, NOW.minusDays(1), NOW.minusDays(1)),
                row(UserSelfRestriction.Action.LIFT_REQUESTED, NOW, NOW.plusDays(7))
        );
        assertTrue(SelfRestrictionService.isActiveAt(history, NOW));
        // Even if we advance time past the cooling — LIFT_REQUESTED alone
        // doesn't flip state. Need explicit LIFTED row (via finaliseLift).
        assertTrue(SelfRestrictionService.isActiveAt(history, NOW.plusDays(8)));
    }

    @Test
    @DisplayName("LIFTED with future effective_at (defensive) → still active")
    void liftedFutureEffectiveStillActive() {
        var history = List.of(
                row(UserSelfRestriction.Action.SET, NOW.minusDays(1), NOW.minusDays(1)),
                row(UserSelfRestriction.Action.LIFTED, NOW, NOW.plusDays(1))
        );
        assertTrue(SelfRestrictionService.isActiveAt(history, NOW),
                "LIFTED whose effective_at hasn't passed should NOT lift the restriction");
    }

    @Test
    @DisplayName("SET → LIFTED → re-SET → active again")
    void reSetAfterLift() {
        var history = List.of(
                row(UserSelfRestriction.Action.SET, NOW.minusDays(30), NOW.minusDays(30)),
                row(UserSelfRestriction.Action.LIFTED, NOW.minusDays(15), NOW.minusDays(15)),
                row(UserSelfRestriction.Action.SET, NOW.minusDays(1), NOW.minusDays(1))
        );
        assertTrue(SelfRestrictionService.isActiveAt(history, NOW));
    }

    @Test
    @DisplayName("full cycle: SET → LIFT_REQUESTED → LIFTED → SET → active")
    void fullCycle() {
        var history = List.of(
                row(UserSelfRestriction.Action.SET, NOW.minusDays(60), NOW.minusDays(60)),
                row(UserSelfRestriction.Action.LIFT_REQUESTED, NOW.minusDays(50), NOW.minusDays(43)),
                row(UserSelfRestriction.Action.LIFTED, NOW.minusDays(42), NOW.minusDays(42)),
                row(UserSelfRestriction.Action.SET, NOW.minusDays(5), NOW.minusDays(5))
        );
        assertTrue(SelfRestrictionService.isActiveAt(history, NOW));
    }
}
