package com.sber.dlmm.pool.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for a swap price quote ({@code POST /api/v1/pools/swap/quote}).
 *
 * <p>Read-only "how much would I get?" estimate — no balances are moved and no
 * state is persisted. The matching response is {@link SwapQuoteResponse}.
 *
 * @param poolId    pool to swap against
 * @param tokenInId the token being sold; must be one of the pool's two tokens.
 *                  Its counterpart is inferred as the token received
 * @param amountIn  amount of {@code tokenInId} to sell, as a raw integer at the
 *                  platform's 10⁻⁴ scale (1 token = 10000 units); must be ≥ 1
 */
public record SwapQuoteRequest(
        @NotNull UUID poolId,
        @NotNull UUID tokenInId,
        @Min(1) long amountIn
) {
}
