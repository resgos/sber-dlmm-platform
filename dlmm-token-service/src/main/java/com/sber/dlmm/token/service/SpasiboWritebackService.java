package com.sber.dlmm.token.service;

import com.sber.dlmm.token.entity.SpasiboWritebackEntry;
import com.sber.dlmm.token.entity.SpasiboWritebackEntry.Status;
import com.sber.dlmm.token.repository.SpasiboWritebackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sprint 9-DS-r4 (P1-17) — SberSpasibo write-back accrual + flush.
 *
 * <p>See {@code docs/SPASIBO-WRITEBACK-DESIGN.md} for the full design.
 * This service owns:
 *  <ol>
 *    <li>{@link #accrue} — called by the swap-event consumer (and
 *        future hooks for HEDGE / ADD_LIQ / CLAIM_FEE). Enforces:
 *        <ul>
 *          <li>Min-transaction threshold (design §R-SP-3 anti-gaming)</li>
 *          <li>Daily + monthly per-user cap (design §3)</li>
 *          <li>Idempotency via DB UNIQUE(dlmm_tx_id)</li>
 *        </ul></li>
 *    <li>{@link #flush} — @Scheduled job that picks up PENDING/RETRY
 *        entries and pushes to Spasibo BU. <b>MVP: logs the
 *        would-be-posted payload; the actual HTTP call lands when
 *        Spasibo BU exposes the sandbox API (Sprint 7 contract gate
 *        per the design doc).</b> Toggle the real call on with
 *        {@code dlmm.spasibo.writeback.endpoint=<url>}.</li>
 *  </ol>
 *
 * <p>Disable wholesale via {@code dlmm.spasibo.writeback.enabled=false}
 * (useful in load tests where accrual would distort the swap path).
 */
@Service
@Slf4j
public class SpasiboWritebackService {

    private final SpasiboWritebackRepository repository;
    private final boolean enabled;
    private final String endpoint;
    private final long capDaily;
    private final long capMonthly;
    private final long minSwapAmount;
    private final long minHedgeAmount;
    private final int maxAttempts;
    private final int batchSize;

    public SpasiboWritebackService(
            SpasiboWritebackRepository repository,
            @Value("${dlmm.spasibo.writeback.enabled:true}") boolean enabled,
            @Value("${dlmm.spasibo.writeback.endpoint:}") String endpoint,
            @Value("${dlmm.spasibo.writeback.cap-daily-points:10000}") long capDaily,
            @Value("${dlmm.spasibo.writeback.cap-monthly-points:200000}") long capMonthly,
            @Value("${dlmm.spasibo.writeback.min-swap-amount:10000}") long minSwapAmount,
            @Value("${dlmm.spasibo.writeback.min-hedge-amount:100000}") long minHedgeAmount,
            @Value("${dlmm.spasibo.writeback.max-attempts:3}") int maxAttempts,
            @Value("${dlmm.spasibo.writeback.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.capDaily = capDaily;
        this.capMonthly = capMonthly;
        this.minSwapAmount = minSwapAmount;
        this.minHedgeAmount = minHedgeAmount;
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
    }

    /**
     * Accrue cashback for a single qualifying DLMM operation.
     *
     * <p>Per-op-type rates from design §3 (in basis points):
     *   SWAP=10, HEDGE=50, ADD_LIQ=5, CLAIM_FEE=10.
     *
     * <p>Returns the persisted entry (or the existing one on
     * idempotency hit). Returns empty when the call was filtered:
     * service disabled, below threshold, cap exceeded, or zero points.
     */
    @Transactional
    public Optional<SpasiboWritebackEntry> accrue(UUID dlmmTxId,
                                                   UUID userId,
                                                   String reasonCode,
                                                   long amountInBaseUnits) {
        if (!enabled) return Optional.empty();
        if (dlmmTxId == null || userId == null || reasonCode == null) return Optional.empty();

        int rateBps = rateForReasonBps(reasonCode);
        if (rateBps <= 0) return Optional.empty();

        long minAmount = minAmountForReason(reasonCode);
        if (amountInBaseUnits < minAmount) {
            // Anti-gaming threshold (design §R-SP-3); silently skip.
            return Optional.empty();
        }

        // Points conversion. amount is in base units (decimals=2 for SRUB,
        // so 100 = 1 ₽). Points are integer Spasibo баллы. Use long math
        // throughout to avoid overflow on hedge-size amounts.
        long points = (amountInBaseUnits * rateBps) / 10_000L;
        if (points <= 0) return Optional.empty();

        // Cap enforcement — sum live accruals over the rolling windows.
        // Refusing rather than capping the value because a partial
        // accrual is more confusing than a missed one (user can re-earn
        // tomorrow if needed).
        LocalDateTime now = LocalDateTime.now();
        long todayUsed = repository.sumPointsByUserSince(userId, now.minusDays(1));
        if (todayUsed + points > capDaily) {
            log.debug("Spasibo daily cap hit for user={}: {} + {} > {}", userId, todayUsed, points, capDaily);
            return Optional.empty();
        }
        long monthUsed = repository.sumPointsByUserSince(userId, now.minusDays(30));
        if (monthUsed + points > capMonthly) {
            log.debug("Spasibo monthly cap hit for user={}: {} + {} > {}", userId, monthUsed, points, capMonthly);
            return Optional.empty();
        }

        SpasiboWritebackEntry entry = SpasiboWritebackEntry.builder()
                .dlmmTxId(dlmmTxId)
                .userId(userId)
                .amountPoints((int) Math.min(points, Integer.MAX_VALUE))
                .reasonCode(reasonCode)
                .status(Status.PENDING)
                .build();
        try {
            SpasiboWritebackEntry saved = repository.save(entry);
            log.info("Spasibo accrued: user={} dlmmTx={} reason={} points={}",
                    userId, dlmmTxId, reasonCode, points);
            return Optional.of(saved);
        } catch (DataIntegrityViolationException dup) {
            // Idempotency hit on UNIQUE(dlmm_tx_id) — same swap event
            // re-delivered. Treat as success, return the existing row
            // so the caller can chain on it.
            return repository.findByDlmmTxId(dlmmTxId);
        }
    }

    /**
     * Scheduled flush: pick up PENDING/RETRY entries and push to
     * Spasibo BU. MVP just logs and marks ACCEPTED (no real HTTP
     * call yet); the actual {@code POST /cashback/credit} lands
     * when the Spasibo BU endpoint is available.
     *
     * <p>Triggered every 30 seconds by default; configurable via
     * {@code dlmm.spasibo.writeback.flush-interval-ms}. Disabled by
     * setting the cron to a far-future expression or
     * {@code dlmm.spasibo.writeback.enabled=false}.
     */
    @Scheduled(fixedDelayString = "${dlmm.spasibo.writeback.flush-interval-ms:30000}")
    @Transactional
    public void flush() {
        if (!enabled) return;
        List<SpasiboWritebackEntry> batch = repository.findShippable(
                List.of(Status.PENDING, Status.RETRY),
                PageRequest.of(0, batchSize));
        if (batch.isEmpty()) return;

        int shipped = 0;
        int failed = 0;
        for (SpasiboWritebackEntry e : batch) {
            try {
                shipToSpasibo(e);
                e.setStatus(Status.ACCEPTED);
                e.setSettledAt(LocalDateTime.now());
                shipped++;
            } catch (Exception ex) {
                e.setAttemptCount(e.getAttemptCount() + 1);
                String err = ex.toString();
                e.setLastError(err.length() > 500 ? err.substring(0, 500) : err);
                e.setStatus(e.getAttemptCount() >= maxAttempts ? Status.DEAD_LETTER : Status.RETRY);
                failed++;
                log.warn("Spasibo write-back failed for entry {} (attempt {}): {}",
                        e.getId(), e.getAttemptCount(), err);
            }
        }
        repository.saveAll(batch);
        if (shipped > 0 || failed > 0) {
            log.info("Spasibo write-back flush: shipped={}, failed={}", shipped, failed);
        }
    }

    /**
     * Spasibo BU push site. Currently a stub: when the endpoint
     * property is empty (default), logs the would-be-posted payload
     * and returns success. When the property is set, real HTTP lands
     * here. See design §5.1 for the wire shape.
     *
     * <p>Extracted so unit tests can verify the accrual + flush
     * orchestration without needing a real HTTP server in the loop.
     */
    protected void shipToSpasibo(SpasiboWritebackEntry e) {
        if (endpoint == null || endpoint.isBlank()) {
            // Sprint 9-DS-r4 MVP — stub. Replace with WebClient POST
            // when Spasibo BU API contract is signed (design §7).
            log.info("[STUB] Would POST {} → {{userId={}, points={}, reason={}, dlmmTx={}}}",
                    "<spasibo-endpoint>", e.getUserId(), e.getAmountPoints(),
                    e.getReasonCode(), e.getDlmmTxId());
            // Synthesise an id so the row carries traceable metadata
            // even before the BU is live.
            e.setSpasiboTxId("stub-" + e.getId());
            return;
        }
        // Real HTTP call goes here in Sprint 10 once Spasibo BU
        // exposes the sandbox API.
        throw new UnsupportedOperationException(
                "Spasibo write-back endpoint set but client not wired (Sprint 10)");
    }

    private int rateForReasonBps(String reasonCode) {
        // Design §3 rate schedule.
        return switch (reasonCode) {
            case SpasiboWritebackEntry.ReasonCodes.SWAP -> 10;          // 0.10%
            case SpasiboWritebackEntry.ReasonCodes.HEDGE -> 50;         // 0.50%
            case SpasiboWritebackEntry.ReasonCodes.ADD_LIQUIDITY -> 5;  // 0.05%
            case SpasiboWritebackEntry.ReasonCodes.CLAIM_FEE -> 10;     // 0.10%
            default -> 0;
        };
    }

    private long minAmountForReason(String reasonCode) {
        // Design §R-SP-3 — anti-gaming threshold per op type.
        // Amounts in base units (decimals=2 for SRUB ⇒ 10_000 base units = 100 ₽).
        if (SpasiboWritebackEntry.ReasonCodes.HEDGE.equals(reasonCode)) {
            return minHedgeAmount;
        }
        return minSwapAmount;
    }
}
