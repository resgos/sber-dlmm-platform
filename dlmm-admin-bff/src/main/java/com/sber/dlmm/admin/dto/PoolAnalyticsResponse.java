package com.sber.dlmm.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 30-day analytics bundle for a single pool, served by
 * {@code GET /api/v1/admin/pools/{id}/analytics} and rendered as the charts on
 * the admin pool-detail view.
 *
 * <p>Aggregated by {@code AdminService} from pool-engine and fee-service; each
 * series degrades to an empty list on a downstream outage. All token quantities
 * in the nested records are raw integers where 1 unit = 10⁻⁴ token (the platform
 * 4-decimal scale); prices are Y/X ratios and are never scaled.
 *
 * @param tvlHistory      time-series of total value locked, split by token side
 * @param volumeHistory   time-series of trading volume
 * @param feeHistory      time-series of fees accrued, split by token side
 * @param binDistribution current liquidity/reserves per price bin
 * @param topLPs          leaderboard of the pool's largest liquidity providers
 */
public record PoolAnalyticsResponse(
    List<TvlHistoryEntry> tvlHistory,
    List<VolumeHistoryEntry> volumeHistory,
    List<FeeHistoryEntry> feeHistory,
    List<BinDistributionEntry> binDistribution,
    List<TopLpEntry> topLPs
) {

    /**
     * One sampled point on the pool's total-value-locked chart.
     *
     * @param timestamp sample time
     * @param tvlX      value locked of the base token X, raw integer (1 unit = 10⁻⁴ token)
     * @param tvlY      value locked of the quote token Y, raw integer (1 unit = 10⁻⁴ token)
     */
    public record TvlHistoryEntry(
        LocalDateTime timestamp,
        long tvlX,
        long tvlY
    ) {}

    /**
     * One sampled point on the pool's trading-volume chart.
     *
     * @param timestamp sample time
     * @param volume    trading volume for the interval, raw integer (1 unit = 10⁻⁴ token)
     */
    public record VolumeHistoryEntry(
        LocalDateTime timestamp,
        long volume
    ) {}

    /**
     * One sampled point on the pool's fee-accrual chart.
     *
     * @param timestamp sample time
     * @param feeX      fees accrued in the base token X, raw integer (1 unit = 10⁻⁴ token)
     * @param feeY      fees accrued in the quote token Y, raw integer (1 unit = 10⁻⁴ token)
     */
    public record FeeHistoryEntry(
        LocalDateTime timestamp,
        long feeX,
        long feeY
    ) {}

    /**
     * Liquidity and reserves held in a single price bin — one bar of the bin
     * distribution histogram.
     *
     * @param binId     bin index (identifies the bin's price step)
     * @param price     bin price as a Y/X ratio (unscaled)
     * @param liquidity bin liquidity {@code L} where {@code reserveX·price + reserveY = L}, raw integer
     * @param reserveX  reserve of the base token X in this bin, raw integer (1 unit = 10⁻⁴ token)
     * @param reserveY  reserve of the quote token Y in this bin, raw integer (1 unit = 10⁻⁴ token)
     */
    public record BinDistributionEntry(
        int binId,
        BigDecimal price,
        long liquidity,
        long reserveX,
        long reserveY
    ) {}

    /**
     * One row of the pool's top-liquidity-provider leaderboard.
     *
     * @param userId         identifier of the liquidity provider
     * @param name           display name of the liquidity provider
     * @param totalLiquidity provider's total liquidity in the pool, raw integer (1 unit = 10⁻⁴ token)
     * @param earnedFees     fees earned by the provider in the pool, raw integer (1 unit = 10⁻⁴ token)
     */
    public record TopLpEntry(
        UUID userId,
        String name,
        long totalLiquidity,
        long earnedFees
    ) {}
}
