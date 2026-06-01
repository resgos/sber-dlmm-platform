package com.sber.dlmm.fee.service;

import com.sber.dlmm.fee.entity.AutoClaimLog;
import com.sber.dlmm.fee.entity.AutoClaimPolicy;
import com.sber.dlmm.fee.entity.FeeAccrual;
import com.sber.dlmm.fee.dto.ClaimFeesRequest;
import com.sber.dlmm.fee.repository.AutoClaimLogRepository;
import com.sber.dlmm.fee.repository.AutoClaimPolicyRepository;
import com.sber.dlmm.fee.repository.FeeAccrualRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sprint 12 G-16 — server-side auto-claim watcher. Swaps the
 * tab-bound frontend MVP for a {@code @Scheduled} sweep that fires
 * regardless of whether the user has a tab open.
 *
 * <p>Algorithm per tick:
 *   1. Pull every {@link AutoClaimPolicy} with {@code enabled=true}.
 *   2. For each policy:
 *      - skip if user has already hit their rolling-24h cap (counted
 *        from {@code auto_claim_log});
 *      - find unclaimed {@link FeeAccrual}s for that user, grouped by
 *        position;
 *      - filter out positions whose pool is in {@code skipPoolIds};
 *      - filter out positions with a SUCCESS log entry within the
 *        last hour (per-position cooldown);
 *      - filter out positions whose total unclaimed (X+Y) is below
 *        {@code thresholdAmount};
 *      - for each survivor, call {@link FeeService#claimFees} and
 *        log SUCCESS or FAILURE.
 *
 * <p>{@code @SchedulerLock} ensures only one fee-service replica
 * processes a tick at a time. Without it, two replicas would race
 * and one would see {@code IdempotencyConflict} (best case) or
 * double-credit (worst case, if the FeeService logic ever diverges).
 *
 * <p>Disabled via {@code dlmm.auto-claim.enabled=false} — useful in
 * tests + the integration env where deterministic ordering matters.
 */
@Component
@ConditionalOnProperty(prefix = "dlmm.auto-claim", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class AutoClaimScheduler {

    /** Min time between two successful fires for the same position. */
    static final Duration PER_POSITION_COOLDOWN = Duration.ofHours(1);
    /** Rolling window for the dailyCap. */
    static final Duration DAILY_CAP_WINDOW = Duration.ofHours(24);

    private final AutoClaimPolicyRepository policyRepository;
    private final AutoClaimLogRepository logRepository;
    private final FeeAccrualRepository feeAccrualRepository;
    private final FeeService feeService;

    /**
     * Defaults to 60s, tunable via property. {@code fixedDelay} so a
     * slow tick doesn't stack. ShedLock gates across replicas;
     * {@code lockAtLeastFor=30s} stops chattier-than-expected clocks
     * from running the sweep twice on the same minute.
     */
    @Scheduled(fixedDelayString = "${dlmm.auto-claim.poll-interval-ms:60000}",
               initialDelayString = "${dlmm.auto-claim.initial-delay-ms:30000}")
    @SchedulerLock(name = "auto-claim", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void tick() {
        long start = System.currentTimeMillis();
        List<AutoClaimPolicy> policies = policyRepository.findByEnabledTrue();
        if (policies.isEmpty()) {
            log.debug("Auto-claim tick at {}: no enabled policies", LocalDateTime.now());
            return;
        }
        int firedOk = 0;
        int firedErr = 0;
        int skipped = 0;
        for (AutoClaimPolicy policy : policies) {
            try {
                int[] result = processPolicy(policy);
                firedOk += result[0];
                firedErr += result[1];
                skipped += result[2];
            } catch (RuntimeException ex) {
                // Per-user failure is recorded but doesn't break the sweep.
                log.warn("Auto-claim tick: processPolicy failed for user={}: {}",
                        policy.getUserId(), ex.toString(), ex);
            }
        }
        long durationMs = System.currentTimeMillis() - start;
        log.info("Auto-claim tick complete: users={}, fired={}, errors={}, skipped={}, durationMs={}",
                policies.size(), firedOk, firedErr, skipped, durationMs);
    }

    /**
     * Returns {@code [fired, errored, skipped]}. Package-private so
     * the test can drive it directly.
     */
    int[] processPolicy(AutoClaimPolicy policy) {
        if (isCappedToday(policy)) {
            log.debug("Auto-claim: user={} hit dailyCap={}, skipping tick",
                    policy.getUserId(), policy.getDailyCap());
            return new int[]{0, 0, 0};
        }

        // Read unclaimed accruals, group by position.
        List<FeeAccrual> unclaimed = feeAccrualRepository.findByUserIdAndClaimedFalse(policy.getUserId());
        if (unclaimed.isEmpty()) return new int[]{0, 0, 0};

        Map<UUID, List<FeeAccrual>> byPosition = unclaimed.stream()
                .collect(Collectors.groupingBy(FeeAccrual::getPositionId));

        Set<String> skipPools = AutoClaimPolicyService.splitSkipPoolIds(policy.getSkipPoolIds())
                .stream().collect(Collectors.toUnmodifiableSet());

        int fired = 0, errored = 0, skipped = 0;
        for (Map.Entry<UUID, List<FeeAccrual>> entry : byPosition.entrySet()) {
            UUID positionId = entry.getKey();
            List<FeeAccrual> accruals = entry.getValue();
            UUID poolId = accruals.get(0).getPoolId();

            if (skipPools.contains(poolId.toString())) {
                skipped++;
                continue;
            }
            if (isOnCooldown(positionId)) {
                skipped++;
                continue;
            }
            long total = accruals.stream().mapToLong(FeeAccrual::getAmount).sum();
            if (BigDecimal.valueOf(total).compareTo(policy.getThresholdAmount()) < 0) {
                skipped++;
                continue;
            }

            // Honour the cap *during* the loop too — if user fires a
            // few positions in a single tick, stop before going over.
            if (isCappedToday(policy)) {
                skipped++;
                continue;
            }

            try {
                var response = feeService.claimFees(
                        new ClaimFeesRequest(positionId, autoClaimIdempotencyKey(policy.getUserId(), positionId)),
                        policy.getUserId());
                logFire(policy.getUserId(), positionId, poolId,
                        response.claimedX(), response.claimedY(),
                        AutoClaimLog.Status.SUCCESS, null);
                fired++;
                log.debug("Auto-claim: user={} position={} fired (X={}, Y={})",
                        policy.getUserId(), positionId, response.claimedX(), response.claimedY());
            } catch (RuntimeException ex) {
                logFire(policy.getUserId(), positionId, poolId, 0L, 0L,
                        AutoClaimLog.Status.FAILURE, truncate(ex.toString(), 500));
                errored++;
                log.warn("Auto-claim: user={} position={} claim failed: {}",
                        policy.getUserId(), positionId, ex.toString());
            }
        }
        return new int[]{fired, errored, skipped};
    }

    /**
     * Per-position cooldown gate: returns {@code true} if this position had a SUCCESS
     * fire within {@link #PER_POSITION_COOLDOWN}. Prevents the sweep from re-claiming the
     * same position every tick (which would spam tiny credits and burn idempotency keys).
     *
     * @param positionId the position to check
     * @return {@code true} if still cooling down; {@code false} if never fired or the cooldown has elapsed
     */
    private boolean isOnCooldown(UUID positionId) {
        Optional<AutoClaimLog> latest = logRepository.findLatestSuccessForPosition(positionId);
        if (latest.isEmpty()) return false;
        Duration sinceLast = Duration.between(latest.get().getFiredAt(), LocalDateTime.now());
        return sinceLast.compareTo(PER_POSITION_COOLDOWN) < 0;
    }

    /**
     * Rolling-24h daily-cap gate: returns {@code true} once the user has reached their
     * configured {@code dailyCap} of SUCCESS fires within {@link #DAILY_CAP_WINDOW}. A cap
     * of {@code 0} means unlimited and short-circuits to {@code false}. This bounds how
     * many automated money movements a user incurs per day.
     *
     * @param policy the user's policy (supplies the cap and the user id)
     * @return {@code true} if the cap is set and already met or exceeded in the window
     */
    private boolean isCappedToday(AutoClaimPolicy policy) {
        if (policy.getDailyCap() <= 0) return false; // 0 = unlimited
        LocalDateTime cutoff = LocalDateTime.now().minus(DAILY_CAP_WINDOW);
        long count = logRepository.countSuccessByUserSince(policy.getUserId(), cutoff);
        return count >= policy.getDailyCap();
    }

    /**
     * Appends one row to {@code auto_claim_log} recording an attempt's outcome. This log
     * is the source of truth for both the cooldown ({@link #isOnCooldown}) and the daily
     * cap ({@link #isCappedToday}), so every fire — SUCCESS or FAILURE — must be written.
     *
     * @param userId       the policy owner
     * @param positionId   the position the claim targeted
     * @param poolId       the pool the position belongs to
     * @param amountX      raw X claimed (0 on failure)
     * @param amountY      raw Y claimed (0 on failure)
     * @param status       {@link AutoClaimLog.Status#SUCCESS} or {@link AutoClaimLog.Status#FAILURE}
     * @param errorMessage truncated failure reason, or {@code null} on success
     */
    private void logFire(UUID userId, UUID positionId, UUID poolId,
                          long amountX, long amountY,
                          AutoClaimLog.Status status, String errorMessage) {
        AutoClaimLog row = AutoClaimLog.builder()
                .userId(userId)
                .positionId(positionId)
                .poolId(poolId)
                .amountX(amountX)
                .amountY(amountY)
                .status(status)
                .errorMessage(errorMessage)
                .build();
        logRepository.save(row);
    }

    /**
     * Deterministic key per (user, position, minute) — protects
     * against double-fire if the scheduler runs faster than the
     * cooldown check in some edge case (e.g. ShedLock disabled, two
     * replicas overlap by a second). FeeService rejects duplicate keys.
     *
     * <p>The minute "bucket" ({@code epochMillis / 60_000}) is what makes two near-simultaneous
     * ticks collide on the same key, so the second is refused rather than double-crediting.
     *
     * @param userId     the policy owner
     * @param positionId the position being claimed
     * @return a stable idempotency key string for this (user, position, minute)
     */
    private static String autoClaimIdempotencyKey(UUID userId, UUID positionId) {
        long bucket = System.currentTimeMillis() / 60_000L;
        return "auto:" + userId + ":" + positionId + ":" + bucket;
    }

    /**
     * Caps a string at {@code max} characters so a long exception message fits the
     * {@code error_message} column. Null-safe.
     *
     * @param s   the string to bound (may be {@code null})
     * @param max maximum length to keep
     * @return {@code s} unchanged if short enough, its first {@code max} chars otherwise, or {@code null}
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
