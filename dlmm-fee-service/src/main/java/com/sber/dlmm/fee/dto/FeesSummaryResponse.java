package com.sber.dlmm.fee.dto;

import java.util.List;
import java.util.UUID;

/**
 * Top-level fee summary for one user: a per-pool breakdown plus cross-pool,
 * cross-token roll-ups that drive the Positions / Dashboard / Profile KPIs.
 *
 * <p>The X/Y totals keep the token legs separate (precise); the
 * {@code totalEarnedX/Y}, {@code totalClaimed} and {@code totalUnclaimed} fields
 * are convenience sums that add both legs together as a smallest-unit proxy —
 * the same convention the admin dashboard uses for TVL/fees (most pools are
 * SRUB-quoted, so it reads as ₽). See the inline note below.
 *
 * @param userId          user this summary belongs to
 * @param byPool          per-pool fee roll-ups
 * @param totalUnclaimedX unclaimed X-leg fees across all pools, raw ×10⁴ base units
 * @param totalUnclaimedY unclaimed Y-leg fees across all pools, raw ×10⁴ base units
 * @param totalEarnedX    lifetime earned X-leg fees across all pools, raw ×10⁴ base units
 * @param totalEarnedY    lifetime earned Y-leg fees across all pools, raw ×10⁴ base units
 * @param totalClaimed    already-claimed fees, both legs summed, raw ×10⁴ base units
 * @param totalUnclaimed  still-unclaimed fees, both legs summed, raw ×10⁴ base units
 */
public record FeesSummaryResponse(
        UUID userId,
        List<PoolFeeSummary> byPool,
        long totalUnclaimedX,
        long totalUnclaimedY,
        // Combined cross-token roll-ups. The frontend FeeSummary contract
        // (Positions / Dashboard / Profile KPIs) reads totalEarnedX/Y,
        // totalClaimed, totalUnclaimed — none of which the response carried
        // before, so every fee KPI rendered "0 ₽" despite real accruals
        // (UI-test F-09). totalClaimed/totalUnclaimed sum both token legs
        // (smallest-unit proxy — same convention the admin dashboard uses
        // for TVL/fees; most pools are SRUB-quoted so it reads as ₽). The
        // precise per-token split stays in byPool / totalUnclaimedX/Y.
        long totalEarnedX,
        long totalEarnedY,
        long totalClaimed,
        long totalUnclaimed
) {}
