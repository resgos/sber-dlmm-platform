package com.sber.dlmm.pool.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for a direct (single-call) swap ({@code POST /api/v1/pools/swap}).
 *
 * <p>Executes immediately: deducts {@code tokenInId} from the caller's balance and
 * credits the output token. For the safer two-step flow that locks a price first,
 * see {@link SwapQuoteRequest} + {@link SwapExecuteRequest}. The result is a
 * {@link SwapResponse}.
 *
 * @param poolId         pool to swap against
 * @param tokenInId      token being sold; must be one of the pool's two tokens
 * @param amountIn       amount of {@code tokenInId} to sell, raw integer at 10⁻⁴
 *                       scale (1 token = 10000 units); must be ≥ 1
 * @param minAmountOut   slippage floor: minimum acceptable output, raw integer at
 *                       10⁻⁴ scale. The swap reverts if the actual output would be
 *                       below this. 0 disables the check
 * @param idempotencyKey optional client-supplied key; a retry with the same key
 *                       returns the original result instead of executing twice.
 *                       May be {@code null}
 */
public record SwapRequest(
        @NotNull UUID poolId,
        @NotNull UUID tokenInId,
        @Min(1) long amountIn,
        long minAmountOut,
        String idempotencyKey
) {
}
