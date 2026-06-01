package com.sber.dlmm.pool.service;

import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.PoolBinRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sprint 9-DS-r4 (P1-12) — nightly TVL rollup reconciliation +
 * drift alarm.
 *
 * <p>{@code LiquidityPool.totalTvlX/Y} are incrementally maintained on
 * every swap, addLiquidity, and removeLiquidity. Any bug in those
 * paths leaks into a slow rollup drift that's invisible until the
 * UI displays a TVL number that doesn't match the bins.
 *
 * <p>This service runs a daily cron that:
 *   1. For every pool, sums {@code reserve_x} and {@code reserve_y}
 *      across all bins via a single SQL aggregate query.
 *   2. Compares the result to the cached rollup on the pool row.
 *   3. Records the drift (absolute and relative) into Micrometer
 *      gauges, broken down per-pool.
 *   4. Emits a WARN log when the relative drift exceeds 0.1%
 *      (a tiny rounding mismatch from BigDecimal → long FLOOR is
 *      expected; 0.1% is well outside it).
 *
 * <p>It does NOT auto-correct the rollup. A drift > 0 means a real
 * code-path bug — we want the operator to see it and decide whether
 * to clobber the rollup or fix the code.
 *
 * <p>Default cron: 03:34 daily (off-peak, offset from the outbox
 * cleanup at 03:17 so we don't pile two heavy jobs at the same
 * minute). Configurable via {@code dlmm.pool.tvl-reconciliation-cron}.
 *
 * <p>Disabled by setting cron to {@code -}; useful in dev / tests.
 */
@Service
public class TvlReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(TvlReconciliationService.class);

    /**
     * Drift ratio above which we emit a WARN log. 0.001 = 0.1%.
     * Small FLOOR rounding from L*c distribution can leak a few units
     * per add/remove cycle; 0.1% is two orders of magnitude above the
     * expected drift floor on dev seed data (verified manually).
     */
    private static final double DRIFT_WARN_THRESHOLD = 0.001;

    private final LiquidityPoolRepository poolRepository;
    private final PoolBinRepository poolBinRepository;
    private final MeterRegistry meterRegistry;

    // Last-run drift snapshots, exposed via Micrometer gauges so the
    // values are queryable from Prometheus between cron runs. Indexed
    // by pool id; auto-registered on first observation.
    private final java.util.concurrent.ConcurrentMap<UUID, AtomicLong> driftXByPool =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<UUID, AtomicLong> driftYByPool =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * @param poolRepository    source of pools and their cached TVL rollups
     * @param poolBinRepository runs the per-pool {@code SUM(reserve_x/y)} aggregate
     * @param meterRegistry     registry the per-pool drift gauges are registered into
     */
    public TvlReconciliationService(LiquidityPoolRepository poolRepository,
                                     PoolBinRepository poolBinRepository,
                                     MeterRegistry meterRegistry) {
        this.poolRepository = poolRepository;
        this.poolBinRepository = poolBinRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Daily cron: for every pool, compare the cached {@code totalTvlX/Y} rollup
     * against a fresh {@code SUM} of all its bin reserves, publish the absolute
     * drift into per-pool Micrometer gauges, and WARN when the relative drift
     * exceeds {@link #DRIFT_WARN_THRESHOLD}.
     *
     * <p>Read-only by design: it never auto-corrects the rollup, because a
     * non-zero drift signals a real bug in a swap/add/remove code path that the
     * operator should see and decide how to handle — silently clobbering the
     * rollup would mask the defect. The gauges persist the last-run drift so
     * it's queryable from Prometheus between cron runs. A tiny FLOOR-rounding
     * drift is expected and stays well under the threshold.
     */
    @Scheduled(cron = "${dlmm.pool.tvl-reconciliation-cron:0 34 3 * * *}")
    @Transactional(readOnly = true)
    public void reconcile() {
        List<LiquidityPool> pools = poolRepository.findAll();
        if (pools.isEmpty()) return;

        int drifted = 0;
        for (LiquidityPool pool : pools) {
            Object[] sums = poolBinRepository.sumReservesByPool(pool.getId());
            // JPA returns Long for SUM; defensive cast through Number so a
            // BigInteger result from Postgres' SUM aggregate doesn't blow up.
            long sumX = sums != null && sums.length > 0 && sums[0] instanceof Number n0 ? n0.longValue() : 0L;
            long sumY = sums != null && sums.length > 1 && sums[1] instanceof Number n1 ? n1.longValue() : 0L;

            long rollupX = pool.getTotalTvlX();
            long rollupY = pool.getTotalTvlY();
            long deltaX = rollupX - sumX;
            long deltaY = rollupY - sumY;

            // Record drift into per-pool gauges.
            driftXByPool.computeIfAbsent(pool.getId(), id -> {
                AtomicLong holder = new AtomicLong();
                Gauge.builder("dlmm.pool.tvl_drift", holder::get)
                        .description("Drift between LiquidityPool.totalTvlX and sum(pool_bins.reserve_x). Non-zero = code-path bug.")
                        .tag("pool", id.toString())
                        .tag("side", "x")
                        .register(meterRegistry);
                return holder;
            }).set(deltaX);
            driftYByPool.computeIfAbsent(pool.getId(), id -> {
                AtomicLong holder = new AtomicLong();
                Gauge.builder("dlmm.pool.tvl_drift", holder::get)
                        .description("Drift between LiquidityPool.totalTvlY and sum(pool_bins.reserve_y). Non-zero = code-path bug.")
                        .tag("pool", id.toString())
                        .tag("side", "y")
                        .register(meterRegistry);
                return holder;
            }).set(deltaY);

            double ratioX = rollupX > 0 ? Math.abs((double) deltaX) / rollupX : 0;
            double ratioY = rollupY > 0 ? Math.abs((double) deltaY) / rollupY : 0;
            double worstRatio = Math.max(ratioX, ratioY);
            if (worstRatio > DRIFT_WARN_THRESHOLD) {
                log.warn(
                        "TVL drift on pool {}: rollupX={} sumX={} deltaX={} ({}%); rollupY={} sumY={} deltaY={} ({}%)",
                        pool.getId(), rollupX, sumX, deltaX, formatPct(ratioX),
                        rollupY, sumY, deltaY, formatPct(ratioY));
                drifted++;
            }
        }

        log.info("TVL reconciliation complete: {} pools checked, {} drift > {}%",
                pools.size(), drifted, DRIFT_WARN_THRESHOLD * 100);
    }

    /**
     * Format a drift ratio as a 4-dp percentage string for the WARN log.
     *
     * @param ratio drift fraction (e.g. {@code 0.001})
     * @return the value ×100 formatted to 4 decimals (e.g. {@code "0.1000"})
     */
    private static String formatPct(double ratio) {
        return String.format("%.4f", ratio * 100);
    }
}
