import type { Pool } from '@/api/types'

/**
 * Daily-turnover floor (≈3.33% of TVL traded per day) that the pool-engine APY
 * model uses as its baseline — mirror of `PoolService.estimateApyPercent`'s
 * "≈ TVL/30 daily turnover" constant. The backend advertises the live 24h fee
 * yield only when it beats this model floor (`spotApy = max(spot, model)`);
 * below it, the shown APY is the per-fee-tier model estimate, not a real reading.
 *
 * The LIVE-vs-MODEL branch is fee-independent: `spotApy ≥ modelApy` reduces to
 * `volume24h / totalTvl ≥ 0.0333` (the fee fraction cancels on both sides), so
 * the client can derive provenance EXACTLY from fields it already has — no extra
 * API field needed. Scale-invariant too: volume and TVL are both ×10⁴ raw
 * quantities, so the ratio is identical whether the values arrive raw or
 * human-scaled.
 */
export const APY_TURNOVER_FLOOR = 0.0333

export type ApyProvenance = 'LIVE' | 'MODEL' | 'NONE'

/**
 * Classify how a pool's advertised APY was derived:
 *  - `'LIVE'`  — driven by real 24h swap volume (turnover ≥ the model floor),
 *  - `'MODEL'` — a per-fee-tier model estimate (thin/zero recent volume),
 *  - `'NONE'`  — pool holds no liquidity, so there is no yield to advertise.
 *
 * Mirrors the pool-engine APY-model branch so the badge never disagrees with
 * the number the backend computed.
 */
export function apyProvenance(
  pool: Pick<Pool, 'volume24h' | 'totalTvlX' | 'totalTvlY'>,
): ApyProvenance {
  const tvl = (pool.totalTvlX ?? 0) + (pool.totalTvlY ?? 0)
  if (tvl <= 0) return 'NONE'
  const turnover = (pool.volume24h ?? 0) / tvl
  return turnover >= APY_TURNOVER_FLOOR ? 'LIVE' : 'MODEL'
}
