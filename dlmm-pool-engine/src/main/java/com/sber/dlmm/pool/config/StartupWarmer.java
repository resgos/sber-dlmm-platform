package com.sber.dlmm.pool.config;

import com.sber.dlmm.pool.service.LiquidityService;
import com.sber.dlmm.pool.service.PoolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Fires once after Spring is fully up and prods the slow paths so the
 * first real user request doesn't pay Spring's lazy-init tax. The
 * Sprint 1 risk register (#14) noted dashboard cold-start at ~2.9s
 * dropping to ~150ms warm; the difference is entirely Hibernate query
 * plan compilation, Hikari pool fill, and Caffeine cache miss.
 *
 * What we warm:
 *   * {@link PoolService#getAllPools(int, int, String)} — triggers the
 *     batch token lookup, fills the Caffeine cache, compiles the
 *     PoolRepository/PoolBinRepository queries.
 *   * {@link LiquidityService#countActivePositions()} — same idea for
 *     the admin dashboard's positions-count call.
 *
 * Errors are caught and logged at WARN — we never want startup warming
 * to crash a healthy app (e.g. token-service is briefly unavailable
 * during a rolling restart).
 */
@Component
public class StartupWarmer {

    private static final Logger log = LoggerFactory.getLogger(StartupWarmer.class);

    private final PoolService poolService;
    private final LiquidityService liquidityService;

    /**
     * @param poolService      used to warm the pool-list hot path (token batch lookup, Caffeine cache, query plans)
     * @param liquidityService used to warm the active-positions-count query the admin dashboard hits
     */
    public StartupWarmer(PoolService poolService, LiquidityService liquidityService) {
        this.poolService = poolService;
        this.liquidityService = liquidityService;
    }

    /**
     * Runs once on {@link ApplicationReadyEvent} and exercises the slow first-
     * request paths (pool list + active-positions count) so the cold-start tax
     * is paid here rather than by the first real user. Any exception is caught
     * and logged at WARN — warming must never crash an otherwise healthy app
     * (e.g. if token-service is briefly down during a rolling restart).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        long started = System.currentTimeMillis();
        try {
            // 20 = same default page size used by the UI, so the hot
            // path warms with the same SQL plan that real users hit.
            poolService.getAllPools(0, 20, "createdAt");
            liquidityService.countActivePositions();
            log.info("Startup warm complete in {} ms", System.currentTimeMillis() - started);
        } catch (Exception ex) {
            log.warn("Startup warm failed (this is non-fatal — first real request will pay cold-start cost): {}",
                    ex.toString());
        }
    }
}
