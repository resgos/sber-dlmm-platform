package com.sber.dlmm.user.service;

import com.sber.dlmm.user.entity.UserSelfRestriction;
import com.sber.dlmm.user.repository.UserSelfRestrictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Sprint 6 #6.7 — самозапрет (115-ФЗ amendment 2024) service.
 *
 * <p>State machine (append-only history):
 * <pre>
 *   (none)
 *      ↓ SET
 *   RESTRICTED ── LIFT_REQUESTED ──(7d)──→ LIFTED ── SET ──→ RESTRICTED ...
 * </pre>
 *
 * <p>Active restriction = latest SET in history with no subsequent LIFTED row
 * whose effective_at is in the past. Effective immediately on SET; lift
 * takes the configured cooling period (default 7 days, per ЦБ РФ guidance).
 *
 * <p>Production needs real ЦБ РФ verification step before LIFTED is honoured
 * — RU-R6 stage 2 in Sprint 7+. Pilot: cooling-period-only enforcement.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SelfRestrictionService {

    private final UserSelfRestrictionRepository repository;

    @Value("${dlmm.self-restriction.lift-cooling-days:7}")
    private long liftCoolingDays;

    /**
     * Idempotent set — if user is already RESTRICTED, returns the latest
     * SET row without inserting a duplicate.
     *
     * <p>Otherwise appends a new {@code SET} row scoped to
     * {@code ALL_NEW_POSITIONS}, effective immediately. Logged at WARN as a
     * compliance-relevant action.
     *
     * @param userId the user placing the restriction on themselves
     * @param reason free-text reason recorded in the history row
     * @return the SET row (new, or the existing latest one if already active)
     */
    @Transactional
    public UserSelfRestriction set(UUID userId, String reason) {
        if (isActive(userId)) {
            log.info("User {} already has active self-restriction — set is no-op", userId);
            return latestSet(userId);
        }
        UserSelfRestriction row = UserSelfRestriction.builder()
                .userId(userId)
                .action(UserSelfRestriction.Action.SET)
                .scope(UserSelfRestriction.Scope.ALL_NEW_POSITIONS)
                .reason(reason)
                .build();
        UserSelfRestriction saved = repository.save(row);
        log.warn("USER SELF-RESTRICTION SET user={} reason={}", userId, reason);
        return saved;
    }

    /**
     * Request lift — starts the cooling period. Returns the LIFT_REQUESTED
     * row with effective_at = now + lift-cooling-days. Until that date,
     * isActive() keeps returning true.
     *
     * <p>This does not lift the restriction; it only opens the regulator-mandated
     * cooling window. The restriction stays active until {@link #finaliseLift}
     * is called after {@code effective_at}.
     *
     * @param userId the restricted user requesting the lift
     * @param reason free-text reason recorded in the history row
     * @return the appended {@code LIFT_REQUESTED} row, carrying its {@code effective_at}
     * @throws IllegalStateException if the user has no active restriction to lift
     */
    @Transactional
    public UserSelfRestriction requestLift(UUID userId, String reason) {
        if (!isActive(userId)) {
            throw new IllegalStateException(
                    "Cannot request lift — user " + userId + " has no active restriction");
        }
        LocalDateTime now = LocalDateTime.now();
        UserSelfRestriction row = UserSelfRestriction.builder()
                .userId(userId)
                .action(UserSelfRestriction.Action.LIFT_REQUESTED)
                .scope(UserSelfRestriction.Scope.ALL_NEW_POSITIONS)
                .reason(reason)
                .createdAt(now)
                .effectiveAt(now.plus(Duration.ofDays(liftCoolingDays)))
                .build();
        UserSelfRestriction saved = repository.save(row);
        log.info("USER SELF-RESTRICTION LIFT_REQUESTED user={} effective_at={}",
                userId, saved.getEffectiveAt());
        return saved;
    }

    /**
     * Finalise the lift after cooling period elapsed. Throws if no
     * eligible LIFT_REQUESTED row exists or cooling hasn't elapsed.
     * (Production CBR verification check plugs in here as Sprint 7+.)
     *
     * <p>Scans history back-to-front for the most recent {@code LIFT_REQUESTED};
     * if found and its {@code effective_at} has passed, appends a {@code LIFTED}
     * row that turns the restriction off. Logged at WARN.
     *
     * @param userId the user whose lift is being finalised
     * @return the appended {@code LIFTED} row
     * @throws IllegalStateException if there is no LIFT_REQUESTED row, or the cooling period has not yet elapsed
     */
    @Transactional
    public UserSelfRestriction finaliseLift(UUID userId) {
        List<UserSelfRestriction> history = repository.findByUserIdOrderByCreatedAtAsc(userId);
        UserSelfRestriction lastLiftReq = null;
        for (int i = history.size() - 1; i >= 0; i--) {
            UserSelfRestriction r = history.get(i);
            if (r.getAction() == UserSelfRestriction.Action.LIFT_REQUESTED) {
                lastLiftReq = r;
                break;
            }
        }
        if (lastLiftReq == null) {
            throw new IllegalStateException("No LIFT_REQUESTED row found for user " + userId);
        }
        if (LocalDateTime.now().isBefore(lastLiftReq.getEffectiveAt())) {
            throw new IllegalStateException(
                    "Cooling period not elapsed; lift available at " + lastLiftReq.getEffectiveAt());
        }
        UserSelfRestriction row = UserSelfRestriction.builder()
                .userId(userId)
                .action(UserSelfRestriction.Action.LIFTED)
                .scope(UserSelfRestriction.Scope.ALL_NEW_POSITIONS)
                .reason("Cooling period elapsed; lift finalised")
                .build();
        UserSelfRestriction saved = repository.save(row);
        log.warn("USER SELF-RESTRICTION LIFTED user={} (cooling elapsed since {})",
                userId, lastLiftReq.getEffectiveAt());
        return saved;
    }

    /**
     * Active = exists SET row newer than any LIFTED row whose effective_at
     * has passed. Pure logic over the history list — easy to test.
     *
     * <p>This is the single gate pool-engine consults (via the internal
     * controller endpoint) before allowing new positions.
     *
     * @param userId the user to evaluate
     * @return true if the user currently has an active self-restriction
     */
    @Transactional(readOnly = true)
    public boolean isActive(UUID userId) {
        return isActiveAt(repository.findByUserIdOrderByCreatedAtAsc(userId), LocalDateTime.now());
    }

    /**
     * Visible-package-private pure function — exposed for unit tests.
     * Walks chronological history; SET = on, LIFTED (if past effective_at) = off.
     * LIFT_REQUESTED does NOT flip state — restriction stays on until cooling elapses
     * AND finaliseLift is invoked.
     *
     * <p>Deterministic fold over the (already chronologically ordered) history,
     * with no I/O — the reason the stateful methods delegate their decision here.
     *
     * @param history the user's restriction events, ordered oldest-first
     * @param atTime  the instant to evaluate "active" as of (lets tests pin time)
     * @return true if the restriction is active at {@code atTime}
     */
    static boolean isActiveAt(List<UserSelfRestriction> history, LocalDateTime atTime) {
        boolean active = false;
        for (UserSelfRestriction r : history) {
            switch (r.getAction()) {
                case SET -> active = true;
                case LIFTED -> {
                    if (!atTime.isBefore(r.getEffectiveAt())) {
                        active = false;
                    }
                }
                case LIFT_REQUESTED -> {
                    /* no state change — see method javadoc */
                }
            }
        }
        return active;
    }

    /**
     * Returns the user's full append-only restriction history, oldest-first.
     * Surfaced to the user via the status endpoint for transparency.
     *
     * @param userId the user whose history to fetch
     * @return all SET / LIFT_REQUESTED / LIFTED rows in chronological order
     */
    @Transactional(readOnly = true)
    public List<UserSelfRestriction> history(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtAsc(userId);
    }

    /**
     * Finds the most recent {@code SET} row for a user by scanning history
     * back-to-front. Used by the idempotent {@link #set} path to return the
     * existing restriction instead of inserting a duplicate.
     *
     * @param userId the user whose latest SET row to find
     * @return the newest {@code SET} row, or null if the user never set a restriction
     */
    private UserSelfRestriction latestSet(UUID userId) {
        List<UserSelfRestriction> history = repository.findByUserIdOrderByCreatedAtAsc(userId);
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).getAction() == UserSelfRestriction.Action.SET) {
                return history.get(i);
            }
        }
        return null;
    }
}
