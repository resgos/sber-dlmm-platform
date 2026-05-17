package com.sber.dlmm.token.fees;

import com.sber.dlmm.token.entity.UserBalance;
import com.sber.dlmm.token.outbox.OutboxService;
import com.sber.dlmm.token.repository.TokenRepository;
import com.sber.dlmm.token.repository.UserBalanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Sprint 3 #3.3 — daily custody fee accrual.
 *
 * Banking-style: holding a balance on the platform costs the holder
 * <i>custody_bps_pa</i> basis points per year, prorated to whatever
 * fraction of the year has passed since the last accrual. By default
 * 5 bps p.a. — i.e. 0.05%/year ≈ 0.000137 bps/day.
 *
 * Mechanics:
 *   1. Find balances with {@code available > 0} that were either
 *      never accrued or last accrued before today's cutoff.
 *   2. For each: compute the days elapsed since the last accrual
 *      (clamped to {@link #MAX_ACCRUAL_DAYS} to bound any single
 *      sweep when a row is years overdue).
 *   3. Deduct prorated fee from user; credit the operator treasury
 *      account; mark {@code last_custody_fee_at = now}.
 *   4. Emit an outbox event per accrual for downstream audit /
 *      analytics. No event = no Kafka noise = no fee taken.
 *
 * Tick interval is 1 hour by default so an outage of up to ~24h
 * still gets caught up in the next tick (sweep is idempotent per row
 * via the cutoff check). The actual accrual is daily because of the
 * cutoff check — repeated ticks within the same day are no-ops.
 *
 * Tunable via env without redeploy:
 *   dlmm.fees.custody-bps-pa  (default 5)
 *   dlmm.fees.custody-tick-cron (default top of every hour)
 *   dlmm.fees.custody-batch (default 500)
 *   dlmm.fees.treasury-user-id (admin acct from init-db seed)
 *
 * Set custody-bps-pa = 0 to disable in dev / staging.
 */
@Component
public class CustodyFeeJob {

    private static final Logger log = LoggerFactory.getLogger(CustodyFeeJob.class);
    private static final long BPS_DIVISOR = 10_000L;
    /** Same SRUB-day count Postgres uses (no leap-day handling needed at these rates). */
    private static final long DAYS_IN_YEAR = 365L;
    /** Don't claw back more than this much in one go — sanity bound for an old row. */
    private static final long MAX_ACCRUAL_DAYS = 90L;

    private final UserBalanceRepository userBalanceRepository;
    private final TokenRepository tokenRepository;
    private final OutboxService outbox;

    @Value("${dlmm.fees.custody-bps-pa:5}")
    private int custodyBpsPerAnnum;

    @Value("${dlmm.fees.custody-batch:500}")
    private int batchSize;

    /**
     * Treasury user ID — receiver of the custody fee. Defaults to the
     * seed admin account so dev / demo just works. In prod this should
     * point to a dedicated "fees collected" virtual user.
     */
    @Value("${dlmm.fees.treasury-user-id:a0000000-0000-0000-0000-000000000001}")
    private UUID treasuryUserId;

    public CustodyFeeJob(UserBalanceRepository userBalanceRepository,
                         TokenRepository tokenRepository,
                         OutboxService outbox) {
        this.userBalanceRepository = userBalanceRepository;
        this.tokenRepository = tokenRepository;
        this.outbox = outbox;
    }

    /**
     * Tick every hour. The cutoff check means only one tick per day
     * actually accrues — the rest are no-ops costing ~1 DB query.
     * Use cron (not fixedDelay) so the timing is predictable.
     */
    @Scheduled(cron = "${dlmm.fees.custody-tick-cron:0 5 * * * *}")
    public void tick() {
        if (custodyBpsPerAnnum <= 0) return;

        LocalDateTime now = LocalDateTime.now();
        // Cutoff = midnight today (UTC). Anything accrued today is skipped.
        LocalDateTime cutoff = now.toLocalDate().atStartOfDay();

        long swept = 0;
        long totalFee = 0;
        while (true) {
            List<UserBalance> batch = userBalanceRepository.findDueForCustodyFee(cutoff, batchSize);
            if (batch.isEmpty()) break;
            for (UserBalance b : batch) {
                long fee = accrueOne(b, now);
                if (fee > 0) totalFee += fee;
                swept++;
            }
            // If we got a smaller-than-full batch, we're done.
            if (batch.size() < batchSize) break;
        }
        if (swept > 0) {
            log.info("Custody fee sweep: balances_swept={}, total_fee_units={}", swept, totalFee);
        }
    }

    /**
     * Per-row accrual. New transaction so a single bad row (e.g. token
     * row deleted while balance survives) doesn't roll back the whole
     * batch. Returns the fee deducted (0 if skipped).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long accrueOne(UserBalance b, LocalDateTime now) {
        LocalDateTime since = b.getLastCustodyFeeAt() != null
                ? b.getLastCustodyFeeAt()
                : b.getUpdatedAt();
        long days = Math.min(MAX_ACCRUAL_DAYS,
                Duration.between(since, now).toDays());
        if (days <= 0) return 0;

        // fee = available * bps_pa / 10000 * days / 365
        // computed in long arithmetic to avoid any FP surprise.
        long fee = (b.getAvailable() * (long) custodyBpsPerAnnum * days)
                / (BPS_DIVISOR * DAYS_IN_YEAR);

        if (fee <= 0) {
            // Tiny balance × tiny bps × few days → 0 rounded down.
            // Still bump last_custody_fee_at so we don't re-evaluate
            // every tick forever; just mark accrued.
            b.setLastCustodyFeeAt(now);
            userBalanceRepository.save(b);
            return 0;
        }
        if (fee >= b.getAvailable()) {
            // Should never happen at 5 bps p.a., but defend against
            // misconfig (e.g. custody-bps-pa = 5000).
            log.warn("Custody fee {} >= available {} on userId={} tokenId={}, skipping",
                    fee, b.getAvailable(), b.getUserId(), b.getTokenId());
            return 0;
        }

        // Deduct from the holder.
        int updated = userBalanceRepository.deductAvailable(
                b.getUserId(), b.getTokenId(), fee);
        if (updated == 0) {
            // Optimistic skip — somebody raced us to update this row.
            // Try again next tick.
            return 0;
        }

        // Credit the treasury account on the same token.
        int credited = userBalanceRepository.creditAvailable(
                treasuryUserId, b.getTokenId(), fee);
        if (credited == 0) {
            // Treasury balance row didn't exist for this token yet.
            // Create it once and retry. This happens only the very
            // first time a new token has its first custody fee taken.
            UserBalance treasury = new UserBalance();
            treasury.setUserId(treasuryUserId);
            treasury.setTokenId(b.getTokenId());
            treasury.setAvailable(0);
            treasury.setLocked(0);
            userBalanceRepository.save(treasury);
            userBalanceRepository.creditAvailable(treasuryUserId, b.getTokenId(), fee);
        }

        // Mark the source row as accrued.
        b.setLastCustodyFeeAt(now);
        userBalanceRepository.save(b);

        // Audit event. Token symbol resolved lazily; not strictly needed.
        String symbol = tokenRepository.findById(b.getTokenId())
                .map(t -> t.getSymbol())
                .orElse("UNKNOWN");
        outbox.append("balance",
                b.getUserId() + ":" + b.getTokenId(),
                "CustodyFeeAccrued",
                "token-events",
                new CustodyFeeAccruedEvent(
                        b.getUserId(), b.getTokenId(), symbol,
                        fee, days, custodyBpsPerAnnum, now));

        return fee;
    }

    /** Audit payload for a custody-fee accrual. */
    public record CustodyFeeAccruedEvent(
            UUID userId,
            UUID tokenId,
            String symbol,
            long feeAmount,
            long daysAccrued,
            int bpsPerAnnum,
            LocalDateTime occurredAt
    ) {}
}
