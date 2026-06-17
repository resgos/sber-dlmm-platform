import type { Position, Pool } from '@/api/types'

/**
 * Mark-to-market value of an LP position expressed in quote (tokenY) units:
 * `currentValueY + currentValueX · price`.
 *
 * Every DLMM pool here is X/SRUB, so tokenY === SRUB and these per-position
 * values sum across the whole book into a single rouble figure. The amounts
 * are the human-scaled ones the API boundary already produced (scalePosition
 * divides raw ×10⁴ → human); `price` is the unscaled Y/X ratio, so `x · price`
 * lands in human-Y units and stays consistent with the per-row P&L column.
 *
 * A missing pool (catalogue not loaded yet) yields price 0 → the X leg is
 * ignored rather than NaN-poisoning the sum.
 */
export function positionValueQuote(
  position: Pick<Position, 'currentValueX' | 'currentValueY'>,
  pool: Pick<Pool, 'currentPrice'> | undefined,
): number {
  const price = pool?.currentPrice ?? 0
  return (position.currentValueY ?? 0) + (position.currentValueX ?? 0) * price
}

/**
 * Portfolio roll-up of {@link positionValueQuote} over the given positions, in
 * SRUB. Drives the "Стоимость позиций" KPI tile — the total liquidity the user
 * currently has deployed, which the page otherwise only exposed per-row.
 */
export function portfolioValueQuote(
  positions: Array<Pick<Position, 'currentValueX' | 'currentValueY' | 'poolId'>>,
  poolById: Map<string, Pick<Pool, 'currentPrice'>>,
): number {
  return positions.reduce((sum, p) => sum + positionValueQuote(p, poolById.get(p.poolId)), 0)
}
