package com.sber.dlmm.pool.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response for a swap price quote ({@code POST /api/v1/pools/swap/quote}).
 *
 * <p>Estimated outcome of selling {@code amountIn} of {@code tokenInId}, computed
 * by walking the bins at the current pool state. Estimates only — the real fill
 * (see {@link SwapResponse}) can differ if the pool moves before execution.
 *
 * @param poolId               pool the quote is for
 * @param tokenInId            token being sold
 * @param tokenOutId           token that would be received (the pool's other token)
 * @param amountIn             input amount echoed back, raw integer at 10⁻⁴ scale
 * @param estimatedAmountOut   expected output amount, raw integer at 10⁻⁴ scale,
 *                             net of fees
 * @param estimatedFee         expected total swap fee, raw integer at 10⁻⁴ scale,
 *                             denominated in the input token
 * @param estimatedFeeBps      effective fee rate in basis points (1 bp = 0.01%);
 *                             NOT scaled. Includes the volatility-driven variable
 *                             component, so it can exceed the pool's base fee
 * @param estimatedBinsCrossed number of price bins the swap would traverse; larger
 *                             values imply more price movement / impact
 * @param estimatedPrice       expected average execution price (token_y per
 *                             token_x ratio), a real decimal — NOT scaled
 * @param priceImpactPct       estimated price impact as a percent (e.g. {@code 0.42}
 *                             = 0.42%), NOT scaled
 */
public record SwapQuoteResponse(
        UUID poolId,
        UUID tokenInId,
        UUID tokenOutId,
        long amountIn,
        long estimatedAmountOut,
        long estimatedFee,
        int estimatedFeeBps,
        int estimatedBinsCrossed,
        BigDecimal estimatedPrice,
        BigDecimal priceImpactPct
) {
}
