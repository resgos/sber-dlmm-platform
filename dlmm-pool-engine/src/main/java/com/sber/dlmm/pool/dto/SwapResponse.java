package com.sber.dlmm.pool.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Result of an executed swap — returned by both the direct swap
 * ({@code POST /api/v1/pools/swap}) and the quote-execute
 * ({@code POST /api/v1/pools/swap/execute}) endpoints.
 *
 * <p>Reports the actual fill (not an estimate): these are the amounts that were
 * really moved on the user's balances and the pool's bins.
 *
 * @param txId           transaction id of the recorded swap, for ledger lookup
 * @param poolId         pool the swap ran against
 * @param tokenInId      token that was sold
 * @param tokenOutId     token that was received
 * @param amountIn       input amount actually charged, raw integer at 10⁻⁴ scale
 * @param amountOut      output amount actually credited, raw integer at 10⁻⁴ scale,
 *                       net of fees
 * @param feeAmount      total swap fee taken, raw integer at 10⁻⁴ scale,
 *                       denominated in the input token
 * @param feeBps         effective fee rate applied, in basis points (1 bp = 0.01%);
 *                       NOT scaled. Reflects the dynamic fee at execution time
 * @param binsCrossed    number of price bins the swap actually traversed
 * @param executionPrice realised average execution price (token_y per token_x
 *                       ratio), a real decimal — NOT scaled
 * @param priceImpactPct realised price impact as a percent (e.g. {@code 0.42} =
 *                       0.42%), NOT scaled
 */
public record SwapResponse(
        UUID txId,
        UUID poolId,
        UUID tokenInId,
        UUID tokenOutId,
        long amountIn,
        long amountOut,
        long feeAmount,
        int feeBps,
        int binsCrossed,
        BigDecimal executionPrice,
        BigDecimal priceImpactPct
) {
}
