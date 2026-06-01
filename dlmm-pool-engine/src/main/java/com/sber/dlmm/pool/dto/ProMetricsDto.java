package com.sber.dlmm.pool.dto;

/**
 * G-23 (Batch #3, 2026-05-26) — pro-grade risk metrics for the Pool
 * comparator.
 *
 * <p>Returned by {@code GET /api/v1/pools/{id}/pro-metrics}. Computed
 * from the last 30 days of {@code price_history} for the pool's
 * non-quote (X-side) token. Replaces the synthetic frontend stub in
 * {@code dlmm-user-ui/src/lib/poolMetrics.ts} with real backend math.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code volatility30d} — annualised stddev of daily log-returns
 *       (sigma × sqrt(365)). Decimal fraction, e.g. {@code 0.0234} =
 *       2.34% annualised vol.
 *   <li>{@code maxDrawdown30d} — worst peak-to-trough decline observed
 *       in the period. Positive number: {@code 0.0567} = 5.67% drop
 *       from the running peak.
 *   <li>{@code sharpe30d} — Sharpe ratio: {@code (mean_daily × 365) /
 *       volatility}. Risk-free rate set to 0 for simplicity (the spec
 *       calls this "cyklicky relevant" — comparing pools, the rf term
 *       cancels). Negative when realised drift was negative.
 *   <li>{@code sampleSize} — number of daily price points the metrics
 *       were computed over. With high-frequency price_history rows we
 *       collapse to one observation per day (mean price), so this caps
 *       at 30. Empty / sparse history surfaces here so the UI can hide
 *       suspicious-looking numbers.
 *   <li>{@code isReliable} — false when {@code sampleSize < 10}. UI
 *       renders a dash-with-tooltip in that case instead of three
 *       deceptive zeros.
 * </ul>
 *
 * <h2>Reliability threshold</h2>
 *
 * <p>10 daily points is the minimum below which annualised volatility
 * estimates degrade fast (the sqrt(365) factor amplifies a noisy
 * stddev). A user reading "Volatility: 2.34%" should be able to trust
 * the magnitude; showing it for 3 data points would mislead. The
 * frontend treats {@code !isReliable} as a render-the-dash signal.
 *
 * @param volatility30d  annualised stddev of daily log-returns, a decimal fraction
 *                       (e.g. {@code 0.0234} = 2.34%); NOT a percent
 * @param maxDrawdown30d worst peak-to-trough decline in the window, a positive
 *                       decimal fraction (e.g. {@code 0.0567} = 5.67% drop)
 * @param sharpe30d      Sharpe ratio (annualised mean ÷ annualised vol, rf = 0);
 *                       dimensionless, can be negative
 * @param sampleSize     number of daily price points used; caps at 30
 * @param isReliable     false when {@code sampleSize < 10}, signalling the UI to hide
 *                       the (then untrustworthy) numbers
 */
public record ProMetricsDto(
        double volatility30d,
        double maxDrawdown30d,
        double sharpe30d,
        int sampleSize,
        boolean isReliable
) {

    /**
     * Sentinel for an empty / unreliable result. Keeps callers from null-checks.
     *
     * @param sampleSize how many daily points were available (below the reliability
     *                   threshold); echoed into the returned DTO
     * @return a DTO with all metrics zeroed and {@code isReliable == false}
     */
    public static ProMetricsDto unreliable(int sampleSize) {
        return new ProMetricsDto(0.0, 0.0, 0.0, sampleSize, false);
    }
}
