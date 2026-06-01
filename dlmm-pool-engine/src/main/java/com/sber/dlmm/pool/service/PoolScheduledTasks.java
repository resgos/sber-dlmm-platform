package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.common.util.FeeCalculator;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Periodic upkeep of the time-decaying pool state that the DLMM variable-fee
 * model depends on but that no swap can advance on its own.
 *
 * <p>Two independent clocks run here against every {@link PoolStatus#ACTIVE}
 * pool:
 * <ul>
 *   <li>the <b>volatility accumulator</b> — bumped up by {@link SwapService} on
 *       each bin crossing and bled back down here so the variable-fee surcharge
 *       relaxes toward the base fee during quiet periods (the EMA-style decay
 *       that makes the fee "dynamic");</li>
 *   <li>the <b>24h rolling volume</b> — the denominator-side input to the
 *       APY/fee-revenue estimate, aged down here to approximate a sliding
 *       window since this module keeps no separate per-swap volume table.</li>
 * </ul>
 *
 * <p>Both tasks are {@code @Transactional} and persist per-pool, so they are
 * write-light but touch every active pool each tick; they are deliberately
 * best-effort (a missed tick simply means slightly staler decay, never a
 * correctness problem) and do not move any token reserves or money.
 */
@Component
public class PoolScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(PoolScheduledTasks.class);
    /**
     * Fallback per-period decay rate (in 1/10000ths → 100 = 1%) used when a
     * pool has no positive {@code decayPeriodSeconds} configured, so the
     * accumulator still relaxes instead of being stuck forever.
     */
    private static final int DEFAULT_DECAY_RATE = 100; // 1% per period

    private final LiquidityPoolRepository poolRepository;

    /**
     * @param poolRepository source/sink for the active-pool rows whose
     *                       volatility accumulator and 24h volume are decayed
     */
    public PoolScheduledTasks(LiquidityPoolRepository poolRepository) {
        this.poolRepository = poolRepository;
    }

    /**
     * Once a minute, relax every active pool's volatility accumulator toward
     * zero so the variable-fee surcharge fades during calm markets.
     *
     * <p>The per-tick decay rate is derived from the pool's own
     * {@code decayPeriodSeconds} (normalised to a 60s tick:
     * {@code 10000 * 60 / decayPeriodSeconds}) so a shorter configured
     * half-life decays faster; pools with a non-positive period fall back to
     * {@link #DEFAULT_DECAY_RATE}. Only pools with a positive accumulator are
     * touched, and a pool is re-saved only when its value actually changed —
     * keeping write churn proportional to genuinely-volatile pools. The actual
     * decay arithmetic is delegated to
     * {@link FeeCalculator#decayVolatilityAccumulator(int, int)} so the rule
     * matches what the fee path expects.
     */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void decayVolatilityAccumulators() {
        List<LiquidityPool> activePools = poolRepository.findByStatus(PoolStatus.ACTIVE);
        int updated = 0;

        for (LiquidityPool pool : activePools) {
            int currentVA = pool.getVolatilityAccumulator();
            if (currentVA > 0) {
                int decayRate = pool.getDecayPeriodSeconds() > 0
                        ? 10_000 * 60 / pool.getDecayPeriodSeconds()
                        : DEFAULT_DECAY_RATE;
                int newVA = FeeCalculator.decayVolatilityAccumulator(currentVA, decayRate);
                if (newVA != currentVA) {
                    pool.setVolatilityAccumulator(newVA);
                    poolRepository.save(pool);
                    updated++;
                }
            }
        }

        if (updated > 0) {
            log.debug("Decayed volatility accumulators for {} pools", updated);
        }
    }

    /**
     * Every five minutes, age each active pool's {@code volume24h} downward to
     * emulate a rolling 24-hour window without a per-swap volume ledger.
     *
     * <p>This module accrues volume incrementally on swaps but has no
     * transactions table to recompute a true trailing sum, so the figure is
     * decayed ~2% per 5-minute tick (288 ticks/day → an approximate 24h
     * roll-off). Because {@code volume24h} feeds the displayed APY/fee-revenue
     * estimate, this keeps an idle pool's headline numbers drifting toward zero
     * rather than reporting yesterday's activity forever. In a production split
     * this would instead aggregate authoritative volume from the
     * transaction-service; the decay is the in-module stand-in.
     */
    @Scheduled(fixedRate = 300_000)
    @Transactional
    public void updateVolume24h() {
        List<LiquidityPool> activePools = poolRepository.findByStatus(PoolStatus.ACTIVE);

        for (LiquidityPool pool : activePools) {
            // Volume24h tracking: since we don't have a separate transactions table
            // in this module, the volume is updated incrementally during swaps.
            // This task serves as a periodic reconciliation/decay point.
            // In production, this would aggregate from the transaction-service.
            // For now, apply a gradual decay to volume24h to simulate rolling window.
            long currentVolume = pool.getVolume24h();
            if (currentVolume > 0) {
                // Decay by ~2% every 5 minutes (288 periods/day → reasonable 24h rolloff)
                long decayed = currentVolume * 98 / 100;
                pool.setVolume24h(decayed);
                poolRepository.save(pool);
            }
        }

        log.debug("Updated volume24h for {} active pools", activePools.size());
    }
}
