package com.sber.dlmm.pool.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sber.dlmm.pool.entity.LpPosition;
import com.sber.dlmm.pool.repository.LpPositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * NEW-4 (Batch #3, 2026-05-26) — per-pool target APY calibration.
 *
 * <p>Replaces the hard-coded 20% target APY that the frontend
 * {@code positionHealth.ts} was using to score fee-yield. Computes a
 * data-driven anchor: the median realised APY across positions in the
 * pool, over a rolling 30-day window. Pools that actually pay 8% will
 * now rate an 8% position as "healthy" instead of "way below 20%".
 *
 * <h2>Adaptation note (deliberate divergence from NEW-4 task spec)</h2>
 *
 * <p>The task spec asked to compute realised APY from positions whose
 * {@code closed_at} fell in the last 30 days, using
 * {@code (currentValueX/Y - initialDepositX/Y)}. The pool-engine data
 * model does <strong>not</strong> preserve close-time values:
 * {@link com.sber.dlmm.pool.service.LiquidityService}'s remove path
 * zeroes both {@code initial_deposit_x/y} and {@code unclaimed_fee_x/y}
 * on a 100% close, and there's no {@code current_value_*} column. The
 * realised-yield formula from the spec is therefore not computable from
 * closed positions in this codebase.
 *
 * <p>We instead use <strong>currently-active positions in the same pool
 * with age &ge; 7 days</strong> as the sample, computing
 * <code>(unclaimedFeeX + unclaimedFeeY) / (initialDepositX + initialDepositY)
 * &times; (365 / age_days)</code> per position and taking the median.
 * This is realised fee-yield annualised (no impermanent-loss term) — a
 * meaningful per-pool calibration anchor that uses the data we
 * actually have. The 7-day floor mirrors the same age-noise guard the
 * frontend uses to weight the age factor.
 *
 * <h2>Fallback</h2>
 *
 * <p>Returns the historical {@code 0.20} (= 20%) default when the
 * sample is too small (&lt; 5 eligible positions). New pools or pools
 * with sparse LPs get the conservative pre-NEW-4 behaviour, avoiding
 * noisy scores driven by 1-2 outlier positions.
 *
 * <h2>Caching</h2>
 *
 * <p>Pool-level APY doesn't change minute-to-minute. Cache the median
 * per {@code poolId} for 1 hour with a 256-entry LRU bound — fits the
 * seed catalogue ~10× over. The cache pattern mirrors
 * {@link com.sber.dlmm.pool.client.TokenServiceClient#tokenCache}.
 *
 * <h2>Failure mode</h2>
 *
 * <p>Bad data (null fields, divide-by-zero, repo throw) must never
 * propagate from this service — the calibration is a soft signal, the
 * fallback always wins. We log + return the default rather than
 * raising. This matches the task spec's "this fallback path should
 * never throw".
 */
@Service
public class PoolApyCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(PoolApyCalibrationService.class);

    /** Pre-NEW-4 default APY anchor. Also the fallback for sparse pools. */
    static final BigDecimal DEFAULT_TARGET_APY = new BigDecimal("0.20");

    /** Minimum sample size before we trust the median. */
    static final int MIN_SAMPLE_SIZE = 5;

    /**
     * Positions younger than this are excluded — fees-per-day on a 1h-old
     * position is a noisy sample. Same threshold the frontend uses to
     * weight its age factor (see {@code positionHealth.ts}).
     */
    static final int MIN_AGE_DAYS = 7;

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    private final LpPositionRepository positionRepository;

    /**
     * Per-pool target APY cache. 1h TTL — pool APYs drift on the scale
     * of hours/days, not minutes; the score is also a soft signal so
     * occasional staleness doesn't matter. 256 entries fits the seed
     * catalogue ~10× over.
     */
    private final Cache<UUID, BigDecimal> targetApyCache = Caffeine.newBuilder()
            .maximumSize(256)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    /**
     * @param positionRepository source of the active positions sampled to
     *                           compute each pool's median realised fee APY
     */
    public PoolApyCalibrationService(LpPositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    /**
     * Median realised fee APY across eligible positions in {@code poolId}.
     *
     * <p>Returns the {@link #DEFAULT_TARGET_APY} when the sample is too
     * small or anything throws. Never raises. Memoised per pool for 1h.
     *
     * @param poolId pool to calibrate (null → default)
     * @return decimal fraction (e.g. {@code 0.08} for 8% APY)
     */
    public BigDecimal getPoolTargetApy(UUID poolId) {
        if (poolId == null) return DEFAULT_TARGET_APY;
        BigDecimal cached = targetApyCache.getIfPresent(poolId);
        if (cached != null) return cached;
        BigDecimal computed = computeTargetApy(poolId);
        targetApyCache.put(poolId, computed);
        return computed;
    }

    /**
     * Test/admin hook — drop the memoised value so the next call
     * recomputes. Useful when a pool's position mix has changed
     * materially (large remove, big new position) and stakeholders
     * don't want to wait an hour. Not exposed via REST yet — would
     * be a Sprint-N admin-bff endpoint.
     *
     * @param poolId pool whose cached APY to evict (null → no-op)
     */
    public void invalidate(UUID poolId) {
        if (poolId != null) targetApyCache.invalidate(poolId);
    }

    /**
     * Compute (uncached) the median realised fee APY across the pool's eligible
     * active positions, falling back to {@link #DEFAULT_TARGET_APY} when the
     * sample is below {@link #MIN_SAMPLE_SIZE} or anything throws.
     *
     * @param poolId pool to calibrate
     * @return the median APY fraction, or the default on a small/empty/failed sample
     */
    private BigDecimal computeTargetApy(UUID poolId) {
        try {
            List<LpPosition> active = positionRepository.findByPoolIdAndIsActiveTrue(poolId);
            if (active == null || active.isEmpty()) {
                return DEFAULT_TARGET_APY;
            }

            LocalDateTime now = LocalDateTime.now();
            List<BigDecimal> apys = new ArrayList<>(active.size());
            for (LpPosition p : active) {
                BigDecimal apy = realisedApy(p, now);
                if (apy != null) apys.add(apy);
            }

            if (apys.size() < MIN_SAMPLE_SIZE) {
                log.debug("PoolApyCalibration[{}] sample={} < {}, falling back to default {}",
                        poolId, apys.size(), MIN_SAMPLE_SIZE, DEFAULT_TARGET_APY);
                return DEFAULT_TARGET_APY;
            }

            BigDecimal median = median(apys);
            log.debug("PoolApyCalibration[{}] median APY={} (sample={})", poolId, median, apys.size());
            return median;
        } catch (RuntimeException ex) {
            // Defensive: any unexpected error degrades to default rather than
            // breaking the health-score endpoint.
            log.warn("PoolApyCalibration[{}] failed, returning default: {}", poolId, ex.toString());
            return DEFAULT_TARGET_APY;
        }
    }

    /**
     * Per-position annualised realised fee yield, or {@code null} if the
     * position isn't a valid sample (too young, zero deposit, missing
     * created-at).
     *
     * <p>Computed as {@code (unclaimedFeeX+Y / initialDepositX+Y) × (365 /
     * ageDays)} via a single {@link BigDecimal} pipeline so small ratios don't
     * underflow. Positions younger than {@link #MIN_AGE_DAYS} are rejected to
     * avoid noisy fees-per-day on a fresh position.
     *
     * @param p   position to measure
     * @param now reference "now" (passed in so a whole sample shares one clock)
     * @return the annualised fee-yield fraction, or {@code null} if not eligible
     */
    private static BigDecimal realisedApy(LpPosition p, LocalDateTime now) {
        if (p == null || p.getCreatedAt() == null) return null;
        long ageSeconds = Duration.between(p.getCreatedAt(), now).getSeconds();
        if (ageSeconds <= 0) return null;
        double ageDays = ageSeconds / 86400.0;
        if (ageDays < MIN_AGE_DAYS) return null;

        long initial = safe(p.getInitialDepositX()) + safe(p.getInitialDepositY());
        if (initial <= 0) return null;

        long fees = safe(p.getUnclaimedFeeX()) + safe(p.getUnclaimedFeeY());
        if (fees < 0) return null;

        // realisedApy = (fees / initial) * (365 / ageDays)
        // Single BigDecimal pipeline so we don't lose precision on small
        // ratios (e.g. 0.0001 / 0.005 underflows naively-rounded doubles).
        BigDecimal feeRatio = new BigDecimal(fees).divide(new BigDecimal(initial), MC);
        BigDecimal annualisationFactor = DAYS_PER_YEAR.divide(BigDecimal.valueOf(ageDays), MC);
        return feeRatio.multiply(annualisationFactor, MC);
    }

    /**
     * Median of a non-empty list. Even-count → average of the two middle
     * values; odd-count → middle. {@link Collections#sort} mutates the
     * input — caller owns the list and is fine with that. Small dataset
     * (caller bounded by {@code findByPoolIdAndIsActiveTrue} size),
     * no need for a streaming statistics library.
     *
     * @param values non-empty list of APY samples; sorted in place
     * @return the median (averaging the two middle values for an even count)
     */
    static BigDecimal median(List<BigDecimal> values) {
        Collections.sort(values);
        int n = values.size();
        if ((n & 1) == 1) {
            return values.get(n / 2);
        }
        BigDecimal lo = values.get(n / 2 - 1);
        BigDecimal hi = values.get(n / 2);
        return lo.add(hi).divide(BigDecimal.valueOf(2), MC).setScale(18, RoundingMode.HALF_UP);
    }

    /**
     * Clamp a possibly-negative raw amount to ≥ 0 before it enters the yield
     * sum, so a stray negative fee/deposit can't distort the median.
     *
     * @param v raw amount
     * @return {@code v} if positive, else 0
     */
    private static long safe(long v) { return Math.max(0L, v); }
}
