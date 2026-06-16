import type { Position } from '@/api/types'

/**
 * Default LP exit fee in basis points (pool-engine `dlmm.fees.lp-exit-bps:10`).
 * 10 bps = 0.10%, charged on withdrawn PRINCIPAL only (never on claimed fees).
 *
 * NOTE: this mirrors the backend default. There is no endpoint exposing the
 * live value, so a non-default admin override would make the estimate drift by
 * the difference — hence the estimate is always presented as "≈".
 */
export const LP_EXIT_FEE_BPS = 10

export interface RemoveEstimate {
  /** Principal returned for each token, net of the exit fee. */
  principalX: number
  principalY: number
  /** Exit fee withheld from the withdrawn principal. */
  exitFeeX: number
  exitFeeY: number
  /** Accrued fees collected — the FULL pending amount, not the removed share. */
  feeX: number
  feeY: number
  /** Bottom line the user receives: net principal + collected fees. */
  totalX: number
  totalY: number
}

/**
 * Estimate what a user receives when removing `percent`% of an LP position,
 * mirroring `LiquidityService.removeLiquidity` crediting:
 *
 *   withdrawnPrincipal = currentValue × percent      (proportional, per-bin shares)
 *   exitFee            = withdrawnPrincipal × bps/1e4 (principal only)
 *   claimedFees        = full pending fees            (a remove always claims ALL
 *                                                      accrued fees, not the share)
 *   credited           = (withdrawnPrincipal − exitFee) + claimedFees
 *
 * Position amounts are already human-scaled at the API boundary; the backend
 * floors at raw-integer (×10⁴) granularity, so this is an estimate (≈), not a
 * to-the-unit quote. Pure + side-effect-free so it unit-tests cleanly.
 *
 * @param pos     the position being (partially) removed
 * @param percent withdrawal percentage 0–100 (clamped)
 * @param exitBps exit fee in bps (defaults to {@link LP_EXIT_FEE_BPS})
 */
export function estimateRemoveReturn(
  pos: Pick<Position, 'currentValueX' | 'currentValueY' | 'unclaimedFeeX' | 'unclaimedFeeY'>,
  percent: number,
  exitBps: number = LP_EXIT_FEE_BPS,
): RemoveEstimate {
  const frac = Math.min(100, Math.max(0, percent)) / 100
  const grossX = Math.max(0, pos.currentValueX ?? 0) * frac
  const grossY = Math.max(0, pos.currentValueY ?? 0) * frac
  const exitFeeX = (grossX * exitBps) / 10_000
  const exitFeeY = (grossY * exitBps) / 10_000
  const principalX = grossX - exitFeeX
  const principalY = grossY - exitFeeY
  // A remove claims the position's full pending fees regardless of percent.
  const feeX = Math.max(0, pos.unclaimedFeeX ?? 0)
  const feeY = Math.max(0, pos.unclaimedFeeY ?? 0)
  return {
    principalX,
    principalY,
    exitFeeX,
    exitFeeY,
    feeX,
    feeY,
    totalX: principalX + feeX,
    totalY: principalY + feeY,
  }
}
