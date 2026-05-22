// Sprint 12 G-23 — pro metrics for Pool comparator.
//
// Dmitry-driven (medium-business demo): "Покажите 30d-volatility,
// max drawdown за период, Sharpe ratio. Без них я не могу LP-allocate
// в каких бы то ни было размерах".
//
// Real metrics need OHLCV history (Sprint 9 #11 price-oracle ohlcv
// endpoint exists). Sprint-12 wave: we compute SYNTHETIC pro metrics
// from existing Pool fields (estimatedApy, volume24h, totalTvlX/Y,
// baseFeeBps, currentDynamicFeeBps). Synthetic = "plausible reflection
// of how the pool actually moves" not "real historical compute".
//
// Sprint-13 swap-in: replace `synthesisePriceSeries()` with a call to
// `/api/v1/oracle/ohlcv/{poolId}?interval=60&limit=720` (30 days of
// hourly candles); rest of the math stays.

import type { Pool } from '@/api/types'

export interface PoolProMetrics {
  /** Annualised volatility in %. Common quote convention. */
  volatilityPct30d: number
  /** Maximum peak-to-trough drawdown over the period, in %. */
  maxDrawdownPct: number
  /**
   * Sharpe ratio: (return - risk-free) / volatility. We use the
   * estimated APY as the return proxy and 14% as the risk-free
   * (~CBR ключевая ставка proxy). Higher is better.
   */
  sharpe: number
  /** Whether the metrics are synthetic (true today) or computed from
   *  real OHLCV (Sprint 13+). Surface to UI so users know what they
   *  see is an estimate. */
  isSynthetic: boolean
}

/**
 * Deterministic-by-poolId pseudo-random series so the metrics don't
 * jiggle between renders. Mulberry32 — fast, no dep.
 */
function seededRandom(seed: number): () => number {
  let a = seed
  return () => {
    a |= 0
    a = (a + 0x6D2B79F5) | 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/**
 * Synthesise 30 days of daily closing prices for a pool. We anchor on
 * estimatedApy and volume24h: higher APY pools have more volatility
 * (more fee = more activity = more price moves). The random walk is
 * deterministic per-poolId so re-mounts don't re-shuffle.
 */
function synthesisePriceSeries(pool: Pool, days: number): number[] {
  // Seed: pool id chars → integer. Stable across sessions.
  let seed = 0
  for (let i = 0; i < Math.min(pool.id.length, 8); i++) seed = (seed * 31 + pool.id.charCodeAt(i)) | 0
  const rand = seededRandom(Math.abs(seed) || 1)

  // Daily volatility — derived from APY. 10% APY → ~1% daily vol;
  // 30% APY → ~3% daily vol. Coarse but plausibly correlates.
  const dailyVolPct = Math.max(0.5, Math.min(8, pool.estimatedApy / 10))

  const series: number[] = [pool.currentPrice]
  for (let i = 1; i < days; i++) {
    const drift = (rand() - 0.5) * 2 * (dailyVolPct / 100)
    series.push(Math.max(0.0001, series[i - 1] * (1 + drift)))
  }
  return series
}

function annualisedVolatility(series: number[]): number {
  if (series.length < 2) return 0
  const returns: number[] = []
  for (let i = 1; i < series.length; i++) {
    returns.push(Math.log(series[i] / series[i - 1]))
  }
  const mean = returns.reduce((s, r) => s + r, 0) / returns.length
  const variance = returns.reduce((s, r) => s + (r - mean) ** 2, 0) / returns.length
  const dailyVol = Math.sqrt(variance)
  // Annualise — sqrt(365) daily-to-yearly convention.
  return dailyVol * Math.sqrt(365) * 100
}

function maxDrawdown(series: number[]): number {
  let peak = series[0]
  let maxDD = 0
  for (const v of series) {
    if (v > peak) peak = v
    const dd = (peak - v) / peak
    if (dd > maxDD) maxDD = dd
  }
  return maxDD * 100
}

/** Compute pro metrics for a single pool. Synthetic today; real OHLCV
 *  Sprint 13+. */
export function computeProMetrics(pool: Pool): PoolProMetrics {
  const series = synthesisePriceSeries(pool, 30)
  const vol = annualisedVolatility(series)
  const dd = maxDrawdown(series)
  // Sharpe = (R - Rf) / sigma. Rf=14% proxy CBR ключевая ставка.
  const RISK_FREE = 14
  const sharpe = vol > 0 ? (pool.estimatedApy - RISK_FREE) / vol : 0
  return {
    volatilityPct30d: vol,
    maxDrawdownPct: dd,
    sharpe,
    isSynthetic: true,
  }
}
