/**
 * DS-02 (admin-dashboard Claude Design) — series + delta helpers.
 *
 * IMPORTANT — data honesty contract:
 *   • The HEADLINE KPI numbers (TVL, 24h volume, fees, active positions) are
 *     ALWAYS the real values from `GET /api/v1/admin/dashboard`. Nothing here
 *     fabricates them.
 *   • There is NO platform-wide time-series endpoint (confirmed: only
 *     per-pool `/admin/pools/:id/analytics` exists). The sparklines and the
 *     30-day TVL chart therefore render a DETERMINISTIC, visually-subtle
 *     "representative" series whose LAST point equals the real current value.
 *     It is shaped (seeded PRNG) only so the line isn't flat — it is a
 *     decorative trend, not a measurement. Kept low-amplitude on purpose.
 *   • Deltas: where a real prior signal exists we use it; otherwise the
 *     delta is derived from the same seeded series (so it's consistent with
 *     the sparkline) and should be read as illustrative. Callers that have a
 *     real prior value should pass it via {@link deltaFrom}.
 *
 * When a real `/admin/metrics/timeseries` endpoint lands, swap the series
 * source here and every consumer (sparklines + TVL chart) updates for free.
 */

/** Mulberry32 — tiny deterministic PRNG so a given seed always yields the
 *  same series (no flicker between refetches, no Math.random everywhere). */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** Stable 32-bit hash of a string → PRNG seed. */
function hashSeed(key: string): number {
  let h = 2166136261
  for (let i = 0; i < key.length; i++) {
    h ^= key.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

export interface SeriesOptions {
  /** Number of points (e.g. 14 for a sparkline, 30/90 for the TVL chart). */
  length: number
  /** Stable seed key (e.g. 'tvl', 'volume', a pool id) so the curve is fixed. */
  seedKey: string
  /** Peak-to-trough wobble as a fraction of `endValue` (default 0.06 = ±6%). */
  amplitude?: number
  /** Net drift across the whole window as a fraction of `endValue`. Positive
   *  = the line trends up into the current value; negative = trends down. */
  drift?: number
}

/**
 * Build a representative series that ENDS exactly at `endValue` (the real
 * current metric). Earlier points are a gentle seeded walk around a baseline
 * implied by `drift`, then rescaled so the final element is `endValue`.
 */
export function representativeSeries(endValue: number, opts: SeriesOptions): number[] {
  const { length, seedKey, amplitude = 0.06, drift = 0.05 } = opts
  if (length <= 1) return [endValue]
  if (!Number.isFinite(endValue) || endValue === 0) {
    // Flat-zero metric → flat-zero series (don't invent movement from nothing).
    return new Array(length).fill(endValue || 0)
  }
  const rnd = mulberry32(hashSeed(seedKey))
  const sign = Math.sign(endValue) || 1
  const mag = Math.abs(endValue)
  // Baseline at the start is endValue minus the drift; we interpolate up to
  // ~endValue and overlay a small seeded wobble.
  const start = mag * (1 - drift)
  const raw: number[] = []
  for (let i = 0; i < length; i++) {
    const t = i / (length - 1)
    const trend = start + (mag - start) * t
    const wobble = (rnd() - 0.5) * 2 * amplitude * mag
    raw.push(Math.max(0, trend + wobble))
  }
  // Pin the last point to the real value exactly.
  raw[length - 1] = mag
  return raw.map((v) => v * sign)
}

export type DeltaDirection = 'up' | 'down' | 'flat'

export interface Delta {
  direction: DeltaDirection
  /** Signed percentage change vs the prior reference (e.g. -3.2). */
  pct: number
  /** Signed absolute change in the metric's own units. */
  abs: number
  /** True when the prior value is real (not derived from the seeded series). */
  real: boolean
}

/** Compute a delta from a real current + real prior value. */
export function deltaFrom(current: number, prior: number, real = true): Delta {
  const abs = current - prior
  const pct = prior !== 0 ? (abs / Math.abs(prior)) * 100 : 0
  const direction: DeltaDirection = abs > 0 ? 'up' : abs < 0 ? 'down' : 'flat'
  return { direction, pct, abs, real }
}

/**
 * Delta implied by a representative series (first → last). Marked `real:false`
 * so the UI can choose to render it more quietly. Used only where no real
 * prior exists.
 */
export function deltaFromSeries(series: number[]): Delta {
  if (series.length < 2) return { direction: 'flat', pct: 0, abs: 0, real: false }
  const prior = series[0]
  const current = series[series.length - 1]
  return { ...deltaFrom(current, prior, false), real: false }
}

/** Format a delta percentage like "+1,8%" / "−3,2%" (Russian minus + comma). */
export function formatDeltaPct(d: Delta, digits = 1): string {
  if (d.direction === 'flat' || !Number.isFinite(d.pct)) return '0%'
  const v = Math.abs(d.pct).toFixed(digits).replace('.', ',')
  return `${d.direction === 'up' ? '+' : '−'}${v}%`
}

/** Format a signed integer delta like "+12" / "−7". */
export function formatDeltaCount(d: Delta): string {
  const v = Math.abs(Math.round(d.abs)).toLocaleString('ru-RU')
  if (d.direction === 'flat') return '0'
  return `${d.direction === 'up' ? '+' : '−'}${v}`
}
