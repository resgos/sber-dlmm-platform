package com.sber.dlmm.pool.dto;

import com.sber.dlmm.common.enums.LiquidityStrategy;

import java.util.List;
import java.util.UUID;

/**
 * Result of a successful add-liquidity call
 * ({@code POST /api/v1/pools/add-liquidity}).
 *
 * <p>Describes the LP position that was opened (or topped up) and the amounts that
 * were actually deposited per the chosen strategy.
 *
 * @param positionId      id of the LP position created/updated; used for later
 *                        remove/claim calls
 * @param poolId          pool the liquidity was added to
 * @param binRangeMin     lowest bin id (inclusive) the position spans
 * @param binRangeMax     highest bin id (inclusive) the position spans
 * @param strategy        distribution strategy that was applied across the range
 * @param depositedX      X-token amount actually deposited, raw integer at 10⁻⁴ scale
 * @param depositedY      Y-token amount actually deposited, raw integer at 10⁻⁴ scale
 * @param liquidityShares liquidity shares minted to the position (internal L-units,
 *                        used to track ownership and pro-rata fee accrual)
 * @param binAllocations  per-bin breakdown of how the deposit was spread across the
 *                        range
 */
public record AddLiquidityResponse(
        UUID positionId,
        UUID poolId,
        int binRangeMin,
        int binRangeMax,
        LiquidityStrategy strategy,
        long depositedX,
        long depositedY,
        long liquidityShares,
        List<BinAllocation> binAllocations
) {
}
