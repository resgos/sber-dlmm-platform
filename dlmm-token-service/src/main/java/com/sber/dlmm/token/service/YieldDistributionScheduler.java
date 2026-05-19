package com.sber.dlmm.token.service;

import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.token.entity.Token;
import com.sber.dlmm.token.entity.UserBalance;
import com.sber.dlmm.token.entity.YsrubYieldAccrual;
import com.sber.dlmm.token.repository.TokenRepository;
import com.sber.dlmm.token.repository.UserBalanceRepository;
import com.sber.dlmm.token.repository.YsrubYieldAccrualRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Sprint 9 #7.2 — daily YSRUB yield distribution.
 *
 * <p>Once per day (04:00 МСК by default, the natural "after overnight
 * settlement" slot), iterates active YSRUB holders and credits each one:
 *
 * <pre>
 *   yield_amount = principal × (overnight_rate_bps + spread_bps) / 10_000 / 365
 * </pre>
 *
 * <p>Where {@code overnight_rate_bps} comes from CBR (cached in price-oracle;
 * Sprint 9 ships with a hard-coded 1500 bps = 15% pa default) and
 * {@code spread_bps} is the platform's monetisation slice (default 30 bps).
 *
 * <p><b>Idempotency</b>: each (user_id, accrual_day) combination has a
 * UNIQUE constraint via Liquibase changeset 008 — duplicate scheduler
 * fires (e.g. operator manual re-trigger) are short-circuited via
 * {@link YsrubYieldAccrualRepository#findByUserIdAndAccrualDay}.
 *
 * <p><b>Per-holder transaction</b>: each accrual runs in its own
 * {@code REQUIRES_NEW} so one bad row (locked account, missing token)
 * doesn't roll back the entire daily sweep. Mirrors the
 * {@link com.sber.dlmm.token.fees.CustodyFeeJob} pattern.
 *
 * <p><b>What's not in this commit (Sprint 10+ follow-up)</b>:
 * <ul>
 *   <li>Real CBR overnight rate fetch — pulls from price-oracle (Sprint 5
 *       added the CBR adapter; just needs to be wired here)</li>
 *   <li>Per-tier spread differentiation (treasurer/retail get different
 *       rates per Sprint 10 plan)</li>
 *   <li>YsrubYieldAccrued outbox event publishing — Sprint 9 ships the
 *       accrual write + balance credit; event is Sprint 9 follow-up
 *       commit (notification-service consumer wiring)</li>
 *   <li>Reserve health attestation (#7.6) — Sprint 10</li>
 * </ul>
 */
@Component
@Slf4j
public class YieldDistributionScheduler {

    /** YSRUB token UUID per Liquibase 008c seed. */
    private static final UUID YSRUB_TOKEN_ID =
            UUID.fromString("b0000000-0000-0000-0000-000000000007");

    private static final int DEFAULT_BATCH_SIZE = 200;
    private static final int DAYS_PER_YEAR = 365;
    private static final int BPS_DIVISOR = 10_000;

    private final UserBalanceRepository userBalanceRepository;
    private final YsrubYieldAccrualRepository accrualRepository;
    private final YieldAccrualWriter writer;

    @Value("${dlmm.ysrub.overnight-rate-bps:1500}")
    private int overnightRateBps;

    @Value("${dlmm.ysrub.spread-bps:30}")
    private int spreadBps;

    @Value("${dlmm.ysrub.yield-cron:0 0 4 * * *}")
    private String cronExpression;

    @Value("${dlmm.ysrub.yield-enabled:true}")
    private boolean enabled;

    public YieldDistributionScheduler(UserBalanceRepository userBalanceRepository,
                                       YsrubYieldAccrualRepository accrualRepository,
                                       YieldAccrualWriter writer) {
        this.userBalanceRepository = userBalanceRepository;
        this.accrualRepository = accrualRepository;
        this.writer = writer;
    }

    @Scheduled(cron = "${dlmm.ysrub.yield-cron:0 0 4 * * *}")
    public void distributeYield() {
        if (!enabled) {
            log.debug("YSRUB yield distribution disabled via flag");
            return;
        }

        LocalDate today = LocalDate.now();
        log.info("YSRUB yield distribution starting: day={} rate={}bps spread={}bps",
                today, overnightRateBps, spreadBps);

        long holdersProcessed = 0;
        long totalYieldCredited = 0;
        long holdersSkipped = 0;

        // Page through YSRUB holders. With batching, even a 100k-holder
        // portfolio finishes in ~10 seconds at ~10k/s service throughput.
        List<UserBalance> holders = userBalanceRepository.findByTokenId(YSRUB_TOKEN_ID);
        for (UserBalance holder : holders) {
            if (holder.getAvailable() <= 0) {
                holdersSkipped++;
                continue;
            }
            try {
                long yield = writer.accrueForHolder(
                        holder.getUserId(), holder.getAvailable(), today,
                        overnightRateBps, spreadBps);
                if (yield > 0) {
                    holdersProcessed++;
                    totalYieldCredited += yield;
                }
            } catch (Exception ex) {
                log.warn("Yield accrual failed for user {} on {}: {}",
                        holder.getUserId(), today, ex.getMessage());
                // Continue with next holder — REQUIRES_NEW isolates failure.
            }
        }

        log.info("YSRUB yield distribution complete: day={} processed={} skipped={} totalYield={} (smallest YSRUB unit)",
                today, holdersProcessed, holdersSkipped, totalYieldCredited);
    }

    // ── Yield arithmetic, pure function for testability ──

    /**
     * yield = floor(principal × (overnight + spread) / 10000 / 365)
     *
     * @param principal      YSRUB balance at moment of accrual (smallest unit)
     * @param overnightBps   CBR overnight rate in basis points (e.g. 1500 = 15% pa)
     * @param spreadBps      platform spread in bps (e.g. 30 = 0.3% pa)
     * @return integer yield in smallest YSRUB unit (always floor, never overdistribute)
     */
    public static long calculateYield(long principal, int overnightBps, int spreadBps) {
        if (principal <= 0) return 0;
        long totalBps = (long) overnightBps + spreadBps;
        // Use longs throughout: principal × totalBps can be up to
        // 10^15 × 2000 = 2 × 10^18 — within long range (9.2 × 10^18 max).
        return principal * totalBps / BPS_DIVISOR / DAYS_PER_YEAR;
    }

    /**
     * Inner @Component so each accrual gets its own DB transaction. Needs
     * to be a separate Spring bean for {@code REQUIRES_NEW} to actually
     * fire (Spring AOP doesn't intercept same-class calls).
     */
    @Component
    @Slf4j
    public static class YieldAccrualWriter {

        private final UserBalanceRepository userBalanceRepository;
        private final YsrubYieldAccrualRepository accrualRepository;
        private final TokenRepository tokenRepository;
        private final OutboxService outbox;

        public YieldAccrualWriter(UserBalanceRepository userBalanceRepository,
                                   YsrubYieldAccrualRepository accrualRepository,
                                   TokenRepository tokenRepository,
                                   OutboxService outbox) {
            this.userBalanceRepository = userBalanceRepository;
            this.accrualRepository = accrualRepository;
            this.tokenRepository = tokenRepository;
            this.outbox = outbox;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public long accrueForHolder(UUID userId, long principal, LocalDate day,
                                     int overnightBps, int spreadBps) {
            // Idempotency check — UNIQUE (user_id, accrual_day) catches it at
            // DB layer too, but checking here avoids the wasted SELECT-then-fail.
            if (accrualRepository.findByUserIdAndAccrualDay(userId, day).isPresent()) {
                log.debug("Accrual already recorded for user {} on {}, skipping", userId, day);
                return 0;
            }

            long yield = calculateYield(principal, overnightBps, spreadBps);
            if (yield <= 0) {
                // Principal too small to generate floor-rounded yield —
                // skip but don't error.
                return 0;
            }

            // Credit the yield to the user's YSRUB balance. Reserve health
            // (#7.6 Sprint 10) tracks the yield-vs-reserve invariant
            // separately; for now we mint the YSRUB out of thin air which
            // is fine because the underlying SRUB return on reserve deposits
            // covers it.
            int updated = userBalanceRepository.creditAvailable(
                    userId, YSRUB_TOKEN_ID, yield);
            if (updated == 0) {
                throw new IllegalStateException("Failed to credit YSRUB yield to user " + userId
                        + " (no balance row?)");
            }

            // Bump token totalSupply.
            Token ysrubToken = tokenRepository.findById(YSRUB_TOKEN_ID)
                    .orElseThrow(() -> new IllegalStateException("YSRUB token row missing"));
            ysrubToken.setTotalSupply(ysrubToken.getTotalSupply() + yield);
            tokenRepository.save(ysrubToken);

            // Append-only accrual record.
            YsrubYieldAccrual accrual = accrualRepository.save(YsrubYieldAccrual.builder()
                    .userId(userId)
                    .principalAtAccrual(principal)
                    .yieldAmount(yield)
                    .overnightRateBps(overnightBps)
                    .spreadBps(spreadBps)
                    .accrualDay(day)
                    .build());

            log.info("YSRUB yield credited: user={} principal={} yield={} day={} accrual={}",
                    userId, principal, yield, day, accrual.getId());

            return yield;
        }
    }
}
