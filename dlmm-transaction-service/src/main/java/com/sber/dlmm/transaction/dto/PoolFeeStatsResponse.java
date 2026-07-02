package com.sber.dlmm.transaction.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 2026-06-17 — effective-fee statistics for one pool, aggregated over its most
 * recent CONFIRMED swaps (the ledger's {@code fee_rate} column, basis points).
 *
 * <p>Backs the user-ui pool-detail line «Эффективная комиссия»: the pool
 * header advertises the static {@code baseFeeBps}, but the rate a trader
 * actually pays includes the volatility-driven variable fee and multi-bin
 * traversal — this is the honest average of what recent swaps really paid.
 *
 * <p>The window is «last N swaps» rather than wall-clock (24h): demo/ledger
 * data is historical, and a time window would flap to empty overnight while
 * a trade-count window stays representative.
 *
 * @param poolId        pool the stats are for
 * @param avgFeeRateBps arithmetic mean of {@code fee_rate} over the sample, 2 dp; null when no swaps
 * @param minFeeRateBps smallest rate in the sample; null when no swaps
 * @param maxFeeRateBps largest rate in the sample; null when no swaps
 * @param swapCount     how many swaps the sample actually contains (≤ sampleLimit)
 * @param sampleLimit   the clamped «last N» the caller asked for
 */
public record PoolFeeStatsResponse(
        UUID poolId,
        BigDecimal avgFeeRateBps,
        BigDecimal minFeeRateBps,
        BigDecimal maxFeeRateBps,
        int swapCount,
        int sampleLimit
) {
}
