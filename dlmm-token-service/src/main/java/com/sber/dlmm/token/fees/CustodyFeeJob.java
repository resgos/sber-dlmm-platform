package com.sber.dlmm.token.fees;

import com.sber.dlmm.common.calendar.BankingCalendarService;
import com.sber.dlmm.token.entity.UserBalance;
import com.sber.dlmm.token.repository.UserBalanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Sprint 3 #3.3 — daily custody fee scheduler.
 *
 * Banking-style: holding a balance on the platform costs the holder
 * <i>custody_bps_pa</i> basis points per year, prorated to whatever
 * fraction of the year has passed since the last accrual. Default 5
 * bps p.a. (~0.05%/year).
 *
 * Mechanics:
 *   1. Find balances with available > 0 that were either never
 *      accrued or last accrued before today's cutoff (midnight UTC).
 *   2. For each: delegate to {@link CustodyFeeAccrualService} which
 *      runs each accrual in its own DB transaction.
 *   3. Repeat in batches of {@link #DEFAULT_BATCH_SIZE} until empty.
 *
 * Per-row transactions ({@code REQUIRES_NEW} on the accrual service)
 * isolate failures: one weird row doesn't roll back the whole sweep.
 *
 * Tick interval is 1 hour by default — the cutoff check makes
 * within-day re-ticks no-ops. Dev overrides cron to fire every 30s
 * for end-to-end verification.
 *
 * Tunable env (no redeploy):
 *   dlmm.fees.custody-bps-pa  (default 5)
 *   dlmm.fees.custody-tick-cron (default 0 5 * * * * — once per hour at xx:05)
 *   dlmm.fees.custody-batch (default 500)
 *   dlmm.fees.treasury-user-id (admin acct from init-db seed)
 *
 * Set custody-bps-pa = 0 to disable the job entirely.
 */
@Component
public class CustodyFeeJob {

    private static final Logger log = LoggerFactory.getLogger(CustodyFeeJob.class);
    private static final int DEFAULT_BATCH_SIZE = 500;

    private final UserBalanceRepository userBalanceRepository;
    private final CustodyFeeAccrualService accrual;
    // Sprint 5 #5.10 — RU banking calendar. Auto-wired from dlmm-common.
    private final BankingCalendarService bankingCalendar;

    @Value("${dlmm.fees.custody-bps-pa:5}")
    private int custodyBpsPerAnnum;

    @Value("${dlmm.fees.custody-batch:500}")
    private int batchSize;

    @Value("${dlmm.fees.treasury-user-id:a0000000-0000-0000-0000-000000000001}")
    private UUID treasuryUserId;

    /**
     * Sprint 5 #5.10 — if true, skip accrual on RU non-banking days
     * (weekends + federal holidays). Default true to match Russian
     * banking conventions; tests / dev can flip to false to keep the
     * job firing every day.
     */
    @Value("${dlmm.fees.custody-skip-holidays:true}")
    private boolean skipHolidays;

    public CustodyFeeJob(UserBalanceRepository userBalanceRepository,
                         CustodyFeeAccrualService accrual,
                         BankingCalendarService bankingCalendar) {
        this.userBalanceRepository = userBalanceRepository;
        this.accrual = accrual;
        this.bankingCalendar = bankingCalendar;
    }

    @Scheduled(cron = "${dlmm.fees.custody-tick-cron:0 5 * * * *}")
    public void tick() {
        if (custodyBpsPerAnnum <= 0) return;

        LocalDateTime now = LocalDateTime.now();

        // Sprint 5 #5.10 — RU banking calendar gate. Custody fee accrues
        // for HOLDING the balance, but conventionally banks freeze
        // accrual on non-business days (no interest on weekends). For
        // the prototype we mirror that. Daily proration arithmetic in
        // CustodyFeeAccrualService is already idempotent — once we
        // skip a day, next working day's accrual covers the elapsed
        // calendar span automatically.
        if (skipHolidays && !bankingCalendar.isWorkingDay(now.toLocalDate())) {
            log.debug("Custody fee tick skipped: {} is a non-banking day", now.toLocalDate());
            return;
        }
        // Cutoff = midnight today. Anything accrued today is skipped
        // — makes within-day re-ticks no-ops.
        LocalDateTime cutoff = now.toLocalDate().atStartOfDay();

        long swept = 0;
        long totalFee = 0;
        int effectiveBatch = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        while (true) {
            List<UserBalance> batch = userBalanceRepository.findDueForCustodyFee(
                    cutoff, effectiveBatch);
            if (batch.isEmpty()) break;
            for (UserBalance b : batch) {
                long fee = accrual.accrueOne(b, now, custodyBpsPerAnnum, treasuryUserId);
                if (fee > 0) totalFee += fee;
                swept++;
            }
            // Partial batch = done. Avoids infinite loop if a row's
            // last_custody_fee_at was set to `now` but its tx hasn't
            // committed yet (in REQUIRES_NEW that's atomic, but defensive).
            if (batch.size() < effectiveBatch) break;
        }
        if (swept > 0) {
            log.info("Custody fee sweep: balances_swept={}, total_fee_units={}",
                    swept, totalFee);
        }
    }
}
