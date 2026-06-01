package com.sber.dlmm.pool.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sber.dlmm.pool.dto.ProMetricsDto;
import com.sber.dlmm.pool.repository.PoolPriceHistoryRepository;
import com.sber.dlmm.pool.repository.PoolPriceHistoryRepository.DailyPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * G-23 (Batch #3, 2026-05-26) — pro-grade risk metrics for the Pool
 * comparator.
 *
 * <p>Computes 30-day realised volatility, max drawdown, and Sharpe
 * ratio from the {@code price_history} table. Replaces the synthetic
 * frontend stub in {@code dlmm-user-ui/src/lib/poolMetrics.ts} with
 * real numbers institutional users (Dmitry persona) need before they
 * size LP allocations.
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li>Pull the daily-mean price series for the pool's primary token
 *       over the last 30 days from {@link PoolPriceHistoryRepository}
 *       (collapsed at the SQL layer — see that class for the join).
 *   <li>If the series has fewer than 10 points, return
 *       {@link ProMetricsDto#unreliable(int)}. Below that threshold
 *       the sqrt(365) annualisation factor amplifies noise into
 *       deceptive double-digit "volatility" numbers.
 *   <li>Compute daily simple returns: {@code (p[t] - p[t-1]) / p[t-1]}.
 *   <li>Volatility = stddev(returns) × sqrt(365). Sample stddev (n-1
 *       divisor) per the standard finance convention — gives the
 *       unbiased estimator we want for a sample.
 *   <li>Sharpe = mean(returns) × 365 / volatility. Risk-free rate = 0
 *       per the G-23 spec ("for simplicity, cyklicky relevant" — when
 *       comparing pools the rf term cancels in the ranking).
 *   <li>MaxDrawdown = max((peak - price) / peak) walking the price
 *       series with a running peak.
 * </ol>
 *
 * <h2>Why {@code double} for the math?</h2>
 *
 * <p>The G-23 spec explicitly authorises {@code double} for statistical
 * aggregation. Volatility / Sharpe / MaxDD are dimensionless ratios in
 * the [-1, 10] range — no money flows through this code path, so the
 * BigDecimal-for-monetary-math rule from CLAUDE.md doesn't apply.
 * {@link Math#sqrt} only works on doubles anyway.
 *
 * <h2>Caching</h2>
 *
 * <p>Pool risk metrics don't change minute-to-minute — they're a
 * 30-day rolling window, so a single price tick moves them imperceptibly.
 * 1-hour Caffeine cache per {@code poolId} mirrors
 * {@link PoolApyCalibrationService}'s pattern. 256-entry LRU bound
 * fits the seed catalogue 10× over.
 *
 * <h2>Failure mode</h2>
 *
 * <p>Any thrown exception inside the compute path degrades to
 * {@code isReliable=false} with zero metrics. This service is on the
 * comparator UX path, not the swap path — a missing chart cell is
 * better than a 500. Same defensive philosophy as
 * {@link PoolApyCalibrationService}.
 */
@Service
public class PoolProMetricsService {

    private static final Logger log = LoggerFactory.getLogger(PoolProMetricsService.class);

    /** Below this many daily points, annualised stats degrade fast. */
    static final int MIN_RELIABLE_SAMPLE = 10;

    /** Rolling window the metrics summarise. */
    static final int WINDOW_DAYS = 30;

    /** Trading-days-per-year convention. Crypto-style 365 (not 252) — */
    /** Russian financial markets trade 5 days a week but our seed catalogue */
    /** is crypto-tilted and the spec literally specifies sqrt(365). */
    static final double DAYS_PER_YEAR = 365.0;

    private final PoolPriceHistoryRepository priceRepository;

    /**
     * Per-pool metrics cache. 1h TTL — see class javadoc on why pool
     * risk metrics tolerate that much staleness. 256 entries fits the
     * seed catalogue with headroom.
     */
    private final Cache<UUID, ProMetricsDto> metricsCache = Caffeine.newBuilder()
            .maximumSize(256)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    /**
     * @param priceRepository source of the daily-mean price series the
     *                        vol/Sharpe/max-drawdown metrics are computed from
     */
    public PoolProMetricsService(PoolPriceHistoryRepository priceRepository) {
        this.priceRepository = priceRepository;
    }

    /**
     * Compute or fetch cached pro metrics for {@code poolId}.
     *
     * @param poolId pool to summarise (null → unreliable empty result)
     * @return metrics with {@code isReliable=false} when the sample is
     *         too small or anything in the pipeline throws. Never null.
     */
    public ProMetricsDto compute(UUID poolId) {
        if (poolId == null) return ProMetricsDto.unreliable(0);
        ProMetricsDto cached = metricsCache.getIfPresent(poolId);
        if (cached != null) return cached;
        ProMetricsDto fresh = computeUncached(poolId);
        metricsCache.put(poolId, fresh);
        return fresh;
    }

    /**
     * Drop the memoised value so the next call recomputes. Useful when
     * a price-oracle backfill lands and stakeholders want to see new
     * numbers before the TTL elapses.
     *
     * @param poolId pool whose cached metrics to evict (null → no-op)
     */
    public void invalidate(UUID poolId) {
        if (poolId != null) metricsCache.invalidate(poolId);
    }

    /**
     * Compute (uncached) the risk metrics for a pool from its 30-day daily-mean
     * price series. Returns {@link ProMetricsDto#unreliable(int)} when fewer
     * than {@link #MIN_RELIABLE_SAMPLE} points exist (annualisation amplifies
     * noise below that) or when anything in the pipeline throws — the comparator
     * page degrades to a missing cell rather than a 500.
     *
     * @param poolId pool to summarise
     * @return computed metrics, or an unreliable result on a thin sample / error
     */
    private ProMetricsDto computeUncached(UUID poolId) {
        try {
            List<DailyPrice> series = priceRepository.findDailyMeanPricesForPool(poolId, WINDOW_DAYS);
            int n = series == null ? 0 : series.size();
            if (n < MIN_RELIABLE_SAMPLE) {
                log.debug("PoolProMetrics[{}] sample={} < {}, returning unreliable", poolId, n, MIN_RELIABLE_SAMPLE);
                return ProMetricsDto.unreliable(n);
            }

            double[] prices = new double[n];
            for (int i = 0; i < n; i++) prices[i] = series.get(i).price();

            double[] returns = dailyReturns(prices);
            double vol = annualisedVolatility(returns);
            double sharpe = sharpeRatio(returns, vol);
            double maxDd = maxDrawdown(prices);

            log.debug("PoolProMetrics[{}] sample={} vol={} maxDd={} sharpe={}",
                    poolId, n, vol, maxDd, sharpe);
            return new ProMetricsDto(vol, maxDd, sharpe, n, true);
        } catch (RuntimeException ex) {
            // Comparator UX path — degrade gracefully on bad data /
            // schema drift / connection blip. Don't 500 the page.
            log.warn("PoolProMetrics[{}] failed, returning unreliable: {}", poolId, ex.toString());
            return ProMetricsDto.unreliable(0);
        }
    }

    /**
     * Daily simple returns: {@code (p[t] - p[t-1]) / p[t-1]}. Spec
     * explicitly says "daily returns" without specifying log vs simple;
     * simple matches the per-position realised-yield formula elsewhere
     * in the codebase and is the more common quote convention for the
     * trader-facing risk-metric trio (vol/maxdd/Sharpe).
     *
     * <p>Skips intervals where the prior price was non-positive — that
     * would divide by zero. With a well-formed price feed this never
     * fires, but the SQL aggregation could produce a zero on a corner
     * case (all prices for the day were null after filtering) and the
     * defensive skip costs nothing.
     *
     * @param prices ordered daily price series
     * @return array of {@code prices.length - 1} simple returns (empty if &lt; 2 prices)
     */
    static double[] dailyReturns(double[] prices) {
        if (prices.length < 2) return new double[0];
        double[] returns = new double[prices.length - 1];
        for (int i = 1; i < prices.length; i++) {
            double prev = prices[i - 1];
            if (prev <= 0.0) {
                returns[i - 1] = 0.0;
            } else {
                returns[i - 1] = (prices[i] - prev) / prev;
            }
        }
        return returns;
    }

    /**
     * Annualised stddev of returns. Sample stddev (n-1 divisor) — the
     * unbiased estimator. Multiplied by sqrt(365) per spec.
     *
     * <p>Returns 0 when fewer than 2 returns are available (no
     * dispersion to measure).
     *
     * @param returns daily simple returns
     * @return annualised volatility (stddev × √365), or 0 for &lt; 2 returns
     */
    static double annualisedVolatility(double[] returns) {
        int n = returns.length;
        if (n < 2) return 0.0;
        double mean = mean(returns);
        double sumSq = 0.0;
        for (double r : returns) {
            double d = r - mean;
            sumSq += d * d;
        }
        double sampleVariance = sumSq / (n - 1);
        return Math.sqrt(sampleVariance) * Math.sqrt(DAYS_PER_YEAR);
    }

    /**
     * Sharpe = (mean_daily × 365) / volatility. rf=0 per spec.
     *
     * <p>Returns 0 when {@code volatility == 0} — division would blow
     * up, and a zero-vol Sharpe is mathematically undefined anyway.
     * A pool with constant price (or only one return point) gets a
     * zero Sharpe, which the UI tints amber ("no signal").
     *
     * @param returns    daily simple returns
     * @param volatility annualised volatility (the denominator)
     * @return the Sharpe ratio, or 0 when there are no returns or zero volatility
     */
    static double sharpeRatio(double[] returns, double volatility) {
        if (returns.length == 0) return 0.0;
        if (volatility <= 0.0) return 0.0;
        double meanDaily = mean(returns);
        return (meanDaily * DAYS_PER_YEAR) / volatility;
    }

    /**
     * Worst peak-to-trough decline. Walk the series with a running
     * peak; at each step compute {@code (peak - price) / peak}; track
     * the maximum. Returns a positive decimal fraction
     * ({@code 0.0567} = 5.67% drawdown).
     *
     * <p>Returns 0 for monotonically-increasing series (no drawdown
     * ever observed) and for series of length &lt; 2.
     *
     * @param prices ordered daily price series
     * @return worst observed peak-to-trough decline as a positive fraction
     */
    static double maxDrawdown(double[] prices) {
        if (prices.length < 2) return 0.0;
        double peak = prices[0];
        double maxDd = 0.0;
        for (double p : prices) {
            if (p > peak) peak = p;
            if (peak <= 0.0) continue;
            double dd = (peak - p) / peak;
            if (dd > maxDd) maxDd = dd;
        }
        return maxDd;
    }

    /**
     * Arithmetic mean of an array, returning 0 for an empty array (so callers
     * needn't guard the length).
     *
     * @param arr values to average
     * @return the mean, or 0 when {@code arr} is empty
     */
    private static double mean(double[] arr) {
        if (arr.length == 0) return 0.0;
        double sum = 0.0;
        for (double v : arr) sum += v;
        return sum / arr.length;
    }
}
