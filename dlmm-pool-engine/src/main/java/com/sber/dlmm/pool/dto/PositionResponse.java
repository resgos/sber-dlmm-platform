package com.sber.dlmm.pool.dto;

import com.sber.dlmm.common.enums.LiquidityStrategy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A user's liquidity position — returned by the positions endpoints
 * (e.g. {@code GET /api/v1/pools/positions}) and rendered on the user's
 * "My positions" / P&L page.
 *
 * <p>Combines static range/strategy metadata with live valuation (current token
 * value and accrued, still-unclaimed fees) and cost basis for P&L.
 *
 * @param id                   position id
 * @param userId               owner of the position
 * @param poolId               pool the liquidity is in
 * @param binRangeMin          lowest bin id (inclusive) the position spans
 * @param binRangeMax          highest bin id (inclusive) the position spans
 * @param strategy             distribution strategy the position was opened with
 * @param totalLiquidityShares total liquidity shares held (internal L-units), summed
 *                             across the position's bins
 * @param currentValueX        current X-token value of the position at the live
 *                             price, raw integer at 10⁻⁴ scale
 * @param currentValueY        current Y-token value of the position at the live
 *                             price, raw integer at 10⁻⁴ scale
 * @param unclaimedFeeX        accrued X-token fees not yet claimed, raw integer at
 *                             10⁻⁴ scale
 * @param unclaimedFeeY        accrued Y-token fees not yet claimed, raw integer at
 *                             10⁻⁴ scale
 * @param initialDepositX      X-token cost basis (sum of deposits, scaled down on
 *                             partial removes), raw integer at 10⁻⁴ scale; 0 for
 *                             legacy positions pre-migration
 * @param initialDepositY      Y-token cost basis (sum of deposits, scaled down on
 *                             partial removes), raw integer at 10⁻⁴ scale; 0 for
 *                             legacy positions pre-migration
 * @param isActive             true while the position still holds liquidity; false
 *                             once fully withdrawn
 * @param createdAt            when the position was opened
 * @param closedAt             when the position was fully closed, or {@code null} if
 *                             still open
 * @param binAllocations       per-bin breakdown of the position's reserves and shares
 * @param tokenXSymbol         display symbol of the pool's X token (audit B3 — so the
 *                             API is self-describing; null if the lookup missed)
 * @param tokenYSymbol         display symbol of the pool's Y token (audit B3; null on miss)
 */
public record PositionResponse(
        UUID id,
        UUID userId,
        UUID poolId,
        int binRangeMin,
        int binRangeMax,
        LiquidityStrategy strategy,
        long totalLiquidityShares,
        long currentValueX,
        long currentValueY,
        long unclaimedFeeX,
        long unclaimedFeeY,
        // Sprint 9-DS-r4 (P1-10) — cost-basis for the PositionsPage
        // P&L column. Sum of all deposits to this position, scaled
        // down proportionally on partial removes. 0 for legacy
        // positions opened before the schema migration.
        long initialDepositX,
        long initialDepositY,
        boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime closedAt,
        List<BinAllocation> binAllocations,
        String tokenXSymbol,
        String tokenYSymbol
) {
}
