package com.sber.dlmm.pool.service;

import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.entity.LpPosition;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.LpPositionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sprint 4 #4.3 — schedules margin-call evaluation across all active LP
 * positions. Split from {@link MarginWatchService} so per-position calls
 * cross the Spring AOP proxy boundary and {@code @Transactional} fires.
 *
 * <p>Pages through active positions (default 500/page) to bound memory.
 * Pool lookups are cached within a single sweep so 100 positions in the
 * same pool only fetch the pool row once.
 *
 * <p>{@code dlmm.margin.enabled=false} disables the scheduler entirely —
 * useful for k6 load tests and dev environments where the noise would
 * obscure other metrics.
 */
@Component
@ConditionalOnProperty(prefix = "dlmm.margin", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class MarginWatchScheduler {

    private final LpPositionRepository positionRepository;
    private final LiquidityPoolRepository poolRepository;
    private final MarginWatchService service;

    @Value("${dlmm.margin.warning-bin-distance:3}")
    private int warningBinDistance;

    @Value("${dlmm.margin.event-cooldown-hours:6}")
    private long eventCooldownHours;

    @Value("${dlmm.margin.page-size:500}")
    private int pageSize;

    /**
     * Default cadence 5 minutes — fast enough to catch active-bin drift
     * in active markets, slow enough that we're not pummeling the DB.
     * Tunable via {@code dlmm.margin.scan-interval-ms}.
     *
     * <p>{@code fixedDelay} (not {@code fixedRate}) so a slow sweep doesn't
     * stack overlapping runs.
     */
    @Scheduled(fixedDelayString = "${dlmm.margin.scan-interval-ms:300000}",
               initialDelayString = "${dlmm.margin.initial-delay-ms:60000}")
    public void sweep() {
        long start = System.currentTimeMillis();
        Duration cooldown = Duration.ofHours(eventCooldownHours);
        Map<UUID, Optional<LiquidityPool>> poolCache = new HashMap<>();

        int page = 0;
        long scanned = 0;
        long emitted = 0;
        long skipped = 0;
        Page<LpPosition> positions;
        do {
            positions = positionRepository.findByIsActiveTrue(PageRequest.of(page, pageSize));
            for (LpPosition position : positions.getContent()) {
                scanned++;
                Optional<LiquidityPool> pool = poolCache.computeIfAbsent(
                        position.getPoolId(), poolRepository::findById);
                if (pool.isEmpty()) {
                    log.warn("Margin sweep: position {} references missing pool {} — skipped",
                            position.getId(), position.getPoolId());
                    skipped++;
                    continue;
                }
                try {
                    boolean fired = service.evaluatePosition(
                            position, pool.get(), warningBinDistance, cooldown);
                    if (fired) emitted++;
                } catch (RuntimeException ex) {
                    // Per-position evaluation is in its own REQUIRES_NEW txn,
                    // so a failure on one position is logged and the sweep
                    // continues. Loud enough to be noticed but doesn't kill
                    // the cycle.
                    log.error("Margin sweep: evaluatePosition failed for position {}: {}",
                            position.getId(), ex.toString(), ex);
                    skipped++;
                }
            }
            page++;
        } while (positions.hasNext());

        long durationMs = System.currentTimeMillis() - start;
        log.info("Margin sweep complete: scanned={} emitted={} skipped={} durationMs={}",
                scanned, emitted, skipped, durationMs);
    }
}
