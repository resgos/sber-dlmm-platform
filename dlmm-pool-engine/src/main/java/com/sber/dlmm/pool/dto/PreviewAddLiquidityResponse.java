package com.sber.dlmm.pool.dto;

import java.util.List;

/**
 * Sprint 11 G-22 — read-only preview of an add-liquidity call.
 *
 * <p>Returned by {@code POST /api/v1/pools/preview-add-liquidity}.
 * Computed entirely from the current pool snapshot and the request — no
 * DB writes, no balance deduction, no idempotency. The UI fetches a
 * fresh preview whenever the user tweaks amounts / range / strategy so
 * Dmitry's "how much does my add shift the price?" question gets
 * answered before he clicks Submit.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code tvlBeforeX / tvlBeforeY} — current pool reserves.
 *   <li>{@code tvlAfterX / tvlAfterY} — pool reserves after the proposed
 *       add is applied (current + this position's deposit).
 *   <li>{@code tvlSharePct} — this position's share of post-add TVL,
 *       computed as {@code (depositX + depositY) / (totalTvlAfterX +
 *       totalTvlAfterY) × 100}. Same mixed-unit assumption the rest of
 *       the engine uses (see Sprint 9-DS-r3 note on canonical L-units).
 *   <li>{@code inRange} — true iff {@code binMin <= activeBinId <= binMax}.
 *       Out-of-range adds earn zero fees until price crosses into the
 *       range.
 *   <li>{@code priceImpactBps} — estimated price shift from adding this
 *       liquidity, in basis points. For in-range adds the impact is 0
 *       (adding to existing liquidity at the current price doesn't move
 *       the active bin). Out-of-range positions get a heuristic based
 *       on bin-distance.
 *   <li>{@code binAllocations} — per-bin breakdown of what would be
 *       deposited (same shape as the live addLiquidity response).
 *   <li>{@code estimatedFeesPerDayY} — rough projection in Y-units:
 *       {@code volume24h × baseFeeBps / 10_000 × yourShare}. Returns 0
 *       if the pool has no recent volume.
 *   <li>{@code warnings} — human-readable strings the UI surfaces as
 *       chips ("out of range", "high concentration", etc.). Empty array
 *       when everything looks fine.
 * </ul>
 */
public record PreviewAddLiquidityResponse(
        long tvlBeforeX,
        long tvlBeforeY,
        long tvlAfterX,
        long tvlAfterY,
        double tvlSharePct,
        boolean inRange,
        int priceImpactBps,
        long depositedX,
        long depositedY,
        List<BinAllocation> binAllocations,
        long estimatedFeesPerDayY,
        List<String> warnings
) {
}
