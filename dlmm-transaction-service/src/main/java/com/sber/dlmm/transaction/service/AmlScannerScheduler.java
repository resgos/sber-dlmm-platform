package com.sber.dlmm.transaction.service;

import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.transaction.entity.AmlAlert;
import com.sber.dlmm.transaction.entity.Transaction;
import com.sber.dlmm.transaction.repository.AmlAlertRepository;
import com.sber.dlmm.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sprint 6 #6.9 — periodic AML scanner.
 *
 * <p>Every 15min (configurable) scans confirmed transactions of the past
 * 24h, groups by user, runs each {@link AmlPatternDetectionService}
 * detector. Hits → persist {@link AmlAlert} row + outbox event to
 * {@code compliance-events} topic + WARN log.
 *
 * <p>Dedup: per-user-per-pattern cooldown window (default 4h) prevents
 * the same alert firing every 15min while the underlying pattern remains.
 *
 * <p>{@code dlmm.aml.enabled=false} disables the scheduler entirely
 * (k6 load runs, dev environments). Disabled by default in tests via
 * a separate test-only properties file.
 */
@Component
@ConditionalOnProperty(prefix = "dlmm.aml", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class AmlScannerScheduler {

    private static final String COMPLIANCE_TOPIC = "compliance-events";

    private final TransactionRepository txRepository;
    private final AmlAlertRepository alertRepository;
    private final OutboxService outbox;
    private final PlatformTransactionManager txManager;

    @Value("${dlmm.aml.scan-interval-ms:900000}")  // 15 min
    private long scanIntervalMs;

    @Value("${dlmm.aml.cooldown-hours:4}")
    private long cooldownHours;

    @Value("${dlmm.aml.window-hours:24}")
    private long windowHours;

    @Value("${dlmm.aml.page-size:5000}")
    private int pageSize;

    /**
     * fixedDelay = 15min by default. Initial delay 2min after boot so
     * a freshly-restarted service doesn't immediately scan against
     * an empty cache.
     */
    @Scheduled(fixedDelayString = "${dlmm.aml.scan-interval-ms:900000}",
               initialDelayString = "${dlmm.aml.initial-delay-ms:120000}")
    public void scan() {
        long start = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = now.minus(Duration.ofHours(windowHours));

        // Page through the FULL window. The previous single page-0 fetch
        // silently dropped every transaction beyond `pageSize` — and because
        // the rows are ordered by userId, it was the highest-UUID users that
        // went unscanned during volume spikes (a 115-ФЗ coverage gap). Bounded
        // by MAX_SCAN_PAGES so a pathological window can't OOM the pod; if the
        // cap is hit we WARN loudly instead of silently truncating.
        final int MAX_SCAN_PAGES = 20;
        List<Transaction> recent = new ArrayList<>();
        int page = 0;
        for (; page < MAX_SCAN_PAGES; page++) {
            List<Transaction> batch = txRepository.findConfirmedInWindow(
                    windowStart, now, PageRequest.of(page, pageSize));
            if (batch.isEmpty()) break;
            recent.addAll(batch);
            if (batch.size() < pageSize) break; // last (partial) page reached
        }
        if (page >= MAX_SCAN_PAGES) {
            log.warn("AML scan hit the {}-page cap ({} txs) for the {}h window — transactions "
                    + "beyond the cap were NOT scanned this run; raise dlmm.aml.page-size if this recurs",
                    MAX_SCAN_PAGES, recent.size(), windowHours);
        }

        if (recent.isEmpty()) {
            log.debug("AML scan: no transactions in last {}h, skipping", windowHours);
            return;
        }

        // Group by user, preserving insertion order (already userId-ordered from query).
        Map<UUID, List<Transaction>> byUser = new LinkedHashMap<>();
        for (Transaction tx : recent) {
            byUser.computeIfAbsent(tx.getUserId(), k -> new ArrayList<>()).add(tx);
        }

        int alertsFired = 0;
        for (Map.Entry<UUID, List<Transaction>> e : byUser.entrySet()) {
            alertsFired += scanOneUser(e.getKey(), e.getValue(), now);
        }

        long durationMs = System.currentTimeMillis() - start;
        log.info("AML scan complete: users={} txs={} alertsFired={} durationMs={}",
                byUser.size(), recent.size(), alertsFired, durationMs);
    }

    /**
     * Runs all 3 detectors for one user. Each fire goes through its own
     * REQUIRES_NEW transaction so persistence + outbox happen together
     * but a failure on user N doesn't roll back user N-1's alerts.
     */
    private int scanOneUser(UUID userId, List<Transaction> userTxs, LocalDateTime now) {
        int fired = 0;
        for (var result : runDetectors(userTxs, now)) {
            if (alreadyAlertedRecently(userId, result.pattern(), now)) {
                continue;
            }
            persistAndPublish(userId, result);
            fired++;
        }
        return fired;
    }

    private List<AmlPatternDetectionService.DetectionResult> runDetectors(
            List<Transaction> userTxs, LocalDateTime now) {
        List<AmlPatternDetectionService.DetectionResult> hits = new ArrayList<>(3);
        AmlPatternDetectionService.detectRoundAmountRepeats(userTxs).ifPresent(hits::add);
        AmlPatternDetectionService.detectFastInFastOut(userTxs).ifPresent(hits::add);
        AmlPatternDetectionService.detectSubThresholdSplit(userTxs, now).ifPresent(hits::add);
        return hits;
    }

    private boolean alreadyAlertedRecently(UUID userId, AmlAlert.Pattern pattern, LocalDateTime now) {
        LocalDateTime cooldownCutoff = now.minus(Duration.ofHours(cooldownHours));
        Optional<AmlAlert> existing = alertRepository
                .findFirstByUserIdAndPatternAndDetectedAtAfterOrderByDetectedAtDesc(
                        userId, pattern, cooldownCutoff);
        if (existing.isPresent()) {
            log.debug("AML alert {} for user {} suppressed (cooldown — last fired {})",
                    pattern, userId, existing.get().getDetectedAt());
            return true;
        }
        return false;
    }

    /**
     * Each alert persistence + outbox emit runs in its own short
     * REQUIRES_NEW transaction so one failure doesn't block other
     * detectors for the same user.
     */
    private void persistAndPublish(UUID userId, AmlPatternDetectionService.DetectionResult result) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AmlAlert saved = tt.execute(status -> {
            AmlAlert alert = AmlAlert.builder()
                    .userId(userId)
                    .pattern(result.pattern())
                    .severity(result.severity())
                    .windowStart(result.windowStart())
                    .windowEnd(result.windowEnd())
                    .transactionCount(result.transactionCount())
                    .totalAmount(result.totalAmount())
                    .evidenceJson(result.evidenceJson())
                    .reviewOutcome(AmlAlert.ReviewOutcome.PENDING)
                    .build();
            AmlAlert s = alertRepository.save(alert);
            outbox.append("aml-alert", s.getId().toString(), result.pattern().name(),
                    COMPLIANCE_TOPIC,
                    new AmlAlertEventPayload(s.getId(), userId, result.pattern(),
                            result.severity(), result.transactionCount(),
                            result.totalAmount(), result.evidenceJson(),
                            s.getDetectedAt()));
            return s;
        });
        log.warn("AML ALERT FIRED user={} pattern={} severity={} txCount={} totalAmount={} alertId={}",
                userId, result.pattern(), result.severity(), result.transactionCount(),
                result.totalAmount(), saved.getId());
    }

    /**
     * Outbox payload — notification-service / compliance email gateway
     * consume from compliance-events topic and route as appropriate.
     */
    public record AmlAlertEventPayload(
            UUID alertId,
            UUID userId,
            AmlAlert.Pattern pattern,
            AmlAlert.Severity severity,
            int transactionCount,
            long totalAmount,
            String evidenceJson,
            LocalDateTime detectedAt
    ) {}
}
