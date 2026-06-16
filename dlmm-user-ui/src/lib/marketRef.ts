export type MarketRefBand = 'fair' | 'slight' | 'wide'

export interface MarketRef {
  /** Oracle reference price, RUB per token X. */
  oraclePrice: number
  /** Signed deviation of the pool spot from the oracle, in percent. */
  deviationPct: number
  /** Coarse bucket for colour/wording: |Δ|<1% fair, <3% slight, else wide. */
  band: MarketRefBand
}

/**
 * Compare a pool's spot price against the independent oracle reference price for
 * the same asset, so a trader sees whether the pool is fairly priced before
 * swapping (a Meteora-style "vs market" signal).
 *
 * Both inputs are RUB-per-token-X: every pool is X/SRUB so {@code pool.currentPrice}
 * is SRUB per X, and the price-oracle serves ₽ per X — directly comparable, no
 * scaling (prices are ratios, never ×10⁴-scaled). Returns null when either price
 * is missing or non-positive (e.g. the oracle has no feed for that asset) so the
 * UI simply shows no signal rather than a bogus 100% deviation.
 */
export function marketReference(
  poolPrice?: number | null,
  oraclePrice?: number | null,
): MarketRef | null {
  if (!poolPrice || !oraclePrice || poolPrice <= 0 || oraclePrice <= 0) return null
  const deviationPct = ((poolPrice - oraclePrice) / oraclePrice) * 100
  const abs = Math.abs(deviationPct)
  const band: MarketRefBand = abs < 1 ? 'fair' : abs < 3 ? 'slight' : 'wide'
  return { oraclePrice, deviationPct, band }
}
