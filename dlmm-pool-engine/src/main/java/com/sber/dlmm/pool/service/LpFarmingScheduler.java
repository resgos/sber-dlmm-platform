package com.sber.dlmm.pool.service;

import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.entity.LpPosition;
import com.sber.dlmm.pool.entity.PoolRewardsConfig;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.LpPositionRepository;
import com.sber.dlmm.pool.repository.PoolRewardsConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sprint 17 — drives LP-farming accrual. A thin sweep (mirrors
 * {@link PoolPriceSyncService}): every cycle it walks each enabled
 * {@link PoolRewardsConfig}, finds the pool's in-range active positions, and
 * delegates the per-position credit to {@link LpFarmingService#accruePosition}
 * — a SEPARATE {@code @Transactional} bean, so each position commits on its own
 * (one bad row doesn't sink the whole sweep) and the proxy actually applies.
 *
 * <p><b>Cycle proration (important):</b> {@code emissionPerDay} is a DAILY
 * budget. A position must NOT receive the full daily emission every cycle, or a
 * pool running hourly would pay 24× its intended rate. We pro-rate the day's
 * emission to the cycle length first:
 * <pre>cycleEmission = emissionPerDay × cycleMs / 86_400_000</pre>
 * then split {@code cycleEmission} across the in-range positions by liquidity.
 * With the default hourly cycle (3_600_000 ms) each cycle distributes 1/24 of
 * the daily budget, so a position in-range for a full day collects ≈ its
 * proportional daily share (minus per-cycle floor dust). {@code cycleMs} is read
 * from the same property that drives {@code fixedRateString}, so the proration
 * always tracks the configured cadence.
 */
@Service
public class LpFarmingScheduler {

    private static final Logger log = LoggerFactory.getLogger(LpFarmingScheduler.class);
    private static final long MS_PER_DAY = 86_400_000L;

    private final PoolRewardsConfigRepository configRepository;
    private final LiquidityPoolRepository poolRepository;
    private final LpPositionRepository positionRepository;
    private final LpFarmingService farmingService;
    private final boolean enabled;
    private final long cycleMs;

    public LpFarmingScheduler(PoolRewardsConfigRepository configRepository,
                              LiquidityPoolRepository poolRepository,
                              LpPositionRepository positionRepository,
                              LpFarmingService farmingService,
                              @Value("${dlmm.farming.enabled:true}") boolean enabled,
                              @Value("${dlmm.farming.accrual-rate-ms:3600000}") long cycleMs) {
        this.configRepository = configRepository;
        this.poolRepository = poolRepository;
        this.positionRepository = positionRepository;
        this.farmingService = farmingService;
        this.enabled = enabled;
        this.cycleMs = cycleMs;
    }

    @Scheduled(fixedRateString = "${dlmm.farming.accrual-rate-ms:3600000}", initialDelay = 30_000)
    public void accrueRewards() {
        if (!enabled) return;

        List<PoolRewardsConfig> configs = configRepository.findByEnabledTrue();
        if (configs.isEmpty()) return;

        int accrued = 0;
        for (PoolRewardsConfig config : configs) {
            try {
                accrued += accrueForPool(config);
            } catch (Exception e) {
                log.warn("LP-farming accrual failed for pool {}: {}", config.getPoolId(), e.toString());
            }
        }
        if (accrued > 0) {
            log.info("LP-farming accrual: credited {} position(s) across {} pool(s)", accrued, configs.size());
        }
    }

    /** Accrue one pool's cycle emission to its in-range active positions; returns positions credited. */
    private int accrueForPool(PoolRewardsConfig config) {
        // Pro-rate the DAILY emission to this cycle (see class javadoc).
        long cycleEmission = config.getEmissionPerDay() * cycleMs / MS_PER_DAY;
        if (cycleEmission <= 0) return 0;

        LiquidityPool pool = poolRepository.findById(config.getPoolId()).orElse(null);
        if (pool == null) return 0;
        int activeBin = pool.getActiveBinId();

        // In-range = pool's active bin within [binRangeMin, binRangeMax]. Filter in-memory.
        List<LpPosition> inRange = positionRepository.findByPoolIdAndIsActiveTrue(config.getPoolId()).stream()
                .filter(p -> activeBin >= p.getBinRangeMin() && activeBin <= p.getBinRangeMax())
                .filter(p -> p.getTotalLiquidityShares() > 0)
                .toList();
        if (inRange.isEmpty()) return 0;

        long totalLiquidity = 0L;
        for (LpPosition p : inRange) {
            totalLiquidity += p.getTotalLiquidityShares();
        }
        if (totalLiquidity <= 0) return 0;

        int credited = 0;
        for (LpPosition p : inRange) {
            long share = LpFarmingService.positionShare(cycleEmission, p.getTotalLiquidityShares(), totalLiquidity);
            if (share <= 0) continue;
            farmingService.accruePosition(p.getId(), pool.getId(), p.getUserId(), config.getRewardTokenId(), share);
            credited++;
        }
        return credited;
    }
}
