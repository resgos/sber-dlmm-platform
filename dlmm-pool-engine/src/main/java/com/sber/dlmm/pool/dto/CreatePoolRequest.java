package com.sber.dlmm.pool.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for creating a new liquidity pool
 * ({@code POST /api/v1/pools}) — an admin/setup operation.
 *
 * <p>Defines the token pair and the pool's fee and bin geometry. The compact
 * constructor applies safe defaults for two optional fields: a non-positive
 * {@code maxVariableFeeBps} defaults to 300 and a non-positive
 * {@code decayPeriodSeconds} defaults to 600. On success a {@link PoolResponse}
 * is returned.
 *
 * @param tokenXId           id of the X (base) token of the pair
 * @param tokenYId           id of the Y (quote) token of the pair
 * @param binStep            spacing between adjacent bins in basis points (1–500);
 *                           fixes the price ratio between consecutive bins. NOT scaled
 * @param baseFeeBps         base swap fee in basis points (1–100), the floor of the
 *                           dynamic fee; NOT scaled
 * @param initialPrice       starting price (token_y per token_x ratio), a real
 *                           decimal — NOT scaled. Sets the initial active bin
 * @param maxVariableFeeBps  cap on the volatility-driven variable fee component, in
 *                           basis points; NOT scaled. Defaults to 300 when ≤ 0
 * @param protocolFeePct     protocol's cut of collected fees, as a whole percent
 *                           (0–5); NOT scaled. 0 opts the pool out of protocol fees
 * @param decayPeriodSeconds half-life, in seconds, over which the volatility
 *                           accumulator decays back toward the base fee. Defaults to
 *                           600 when ≤ 0
 */
public record CreatePoolRequest(
        @NotNull UUID tokenXId,
        @NotNull UUID tokenYId,
        @Min(1) @Max(500) int binStep,
        @Min(1) @Max(100) int baseFeeBps,
        @NotNull BigDecimal initialPrice,
        int maxVariableFeeBps,
        // Sprint 9-DS-r4 (P1-15) — cap mirrors the 3.A legal memo
        // ceiling. >5% requires broker-dealer reg re-evaluation; we
        // refuse the request rather than silently clamp. Lower bound
        // is 0 (opt-out is the safe default; admin turns it on
        // per-pool via /api/v1/pools/{id}/protocol-fee).
        @Min(0) @Max(5) int protocolFeePct,
        int decayPeriodSeconds
) {
    public CreatePoolRequest {
        if (maxVariableFeeBps <= 0) maxVariableFeeBps = 300;
        // Sprint 9-DS-r4 (P1-15) — silent bump from 0 → 20 dropped.
        // 0 is now a valid explicit opt-out and must be preserved.
        // Out-of-range values are rejected at the validation layer
        // above, not coerced.
        if (decayPeriodSeconds <= 0) decayPeriodSeconds = 600;
    }
}
