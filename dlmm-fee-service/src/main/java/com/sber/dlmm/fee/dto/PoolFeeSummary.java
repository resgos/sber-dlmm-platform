package com.sber.dlmm.fee.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-pool roll-up of a user's fees: how much is still unclaimed and how much
 * has been earned in total, split by token leg. One of these is produced for
 * each pool the user has accruals in, inside {@link FeesSummaryResponse#byPool()}.
 *
 * @param poolId          the pool
 * @param poolName        human {@code "TX/TY"} pair label, resolved server-side from the
 *                        shared {@code liquidity_pools}/{@code tokens} catalogue
 *                        (falls back to the pool id string if it can't be read)
 * @param unclaimedFeeX   still-unclaimed X-leg fees, raw ×10⁴ base units
 * @param unclaimedFeeY   still-unclaimed Y-leg fees, raw ×10⁴ base units
 * @param totalEarnedFeeX lifetime earned X-leg fees (claimed + unclaimed), raw ×10⁴ base units
 * @param totalEarnedFeeY lifetime earned Y-leg fees (claimed + unclaimed), raw ×10⁴ base units
 * @param estimatedApyPct estimated APY as a whole-number percent (e.g. 12.5 = 12.5%),
 *                        NOT scaled; currently a placeholder of {@code 0}
 */
public record PoolFeeSummary(
        UUID poolId,
        String poolName,
        long unclaimedFeeX,
        long unclaimedFeeY,
        long totalEarnedFeeX,
        long totalEarnedFeeY,
        BigDecimal estimatedApyPct
) {}
