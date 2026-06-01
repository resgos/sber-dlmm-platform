package com.sber.dlmm.pool.dto;

import com.sber.dlmm.common.enums.LiquidityStrategy;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for adding liquidity to a pool
 * ({@code POST /api/v1/pools/add-liquidity}), opening a new LP position spread
 * over a bin range according to a distribution strategy.
 *
 * <p>Supports single-sided deposits (one of {@code amountX}/{@code amountY} may be
 * 0); the service rejects both-zero. On success returns an {@link AddLiquidityResponse}.
 *
 * @param poolId         pool to deposit into
 * @param amountX        amount of the pool's X (base) token to deposit, raw integer
 *                       at 10⁻⁴ scale; 0 allowed for a one-sided (Y-only) add
 * @param amountY        amount of the pool's Y (quote) token to deposit, raw integer
 *                       at 10⁻⁴ scale; 0 allowed for a one-sided (X-only) add
 * @param binRangeMin    lowest bin id (inclusive) the liquidity spans; lower bins
 *                       correspond to lower prices
 * @param binRangeMax    highest bin id (inclusive) the liquidity spans
 * @param strategy       how the deposit is distributed across the bin range
 *                       (e.g. SPOT / CURVE / BID_ASK)
 * @param idempotencyKey optional client key; a retry with the same key returns the
 *                       original result instead of opening a second position.
 *                       May be {@code null}
 */
public record AddLiquidityRequest(
        @NotNull UUID poolId,
        // Sprint 16 (Meteora parity) — @Min(0) (was @Min(1)) enables SINGLE-SIDED
        // liquidity: deposit only X or only Y (the other side = 0). The service
        // rejects both-zero. The bin distribution already routes X to bins ≥ active
        // and Y to bins ≤ active, so a one-sided deposit lands on the correct side.
        @Min(0) long amountX,
        @Min(0) long amountY,
        int binRangeMin,
        int binRangeMax,
        @NotNull LiquidityStrategy strategy,
        String idempotencyKey
) {
}
