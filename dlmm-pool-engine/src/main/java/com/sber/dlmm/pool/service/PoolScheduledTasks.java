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

@Component
public class PoolScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(PoolScheduledTasks.class);
    private static final int DEFAULT_DECAY_RATE = 100; // 1% per period

    private final LiquidityPoolRepository poolRepository;

    public PoolScheduledTasks(LiquidityPoolRepository poolRepository) {
        this.poolRepository = poolRepository;
    }

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
