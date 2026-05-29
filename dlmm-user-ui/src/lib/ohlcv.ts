// PC-02 (2026-05-29, FEATURE-BATCH-PLAN) — timeframe handling for the
// price chart.
//
// REAL-DATA HONESTY (per the unit brief): the price-oracle only stores
// and serves 1-minute candles — `OhlcvQueryService.getCandles` returns
// an EMPTY list for any interval ≠ 60 (it explicitly refuses to "silently
// serve empty arrays" for unimplemented roll-ups). So the higher
// timeframes (5м/1ч/1д) are produced HERE by re-bucketing the real 1m
// candles the backend already gave us. This is a lossless roll-up of OUR
// internal data — NOT fabricated candles. We never invent a bar that has
// no underlying 1m swap activity: a 1h bucket only exists if ≥1 real 1m
// candle falls inside it.
//
// (If/when the backend grows a real hourly/daily roll-up — noted in the
// PC-02 report as a suggested transactions→OHLCV backfill — switch the
// fetch to pass the real interval and drop the client aggregation.)

import type { OhlcvCandle } from '@/api/types'

export type TimeframeKey = '1m' | '5m' | '1h' | '1d'

export interface TimeframeDef {
  key: TimeframeKey
  /** RU label for the Segmented control */
  label: string
  /** target bucket width in seconds */
  bucketSec: number
  /** how many raw 1m candles to request so the bucketed view is full-ish */
  rawLimit: number
}

// Ordered for the Segmented control. `bucketSec` mirrors the interval the
// user mentally selects; the backend interval we actually hit is always
// 60 (see module header). rawLimit is sized so each timeframe shows a
// reasonable window without blowing past the backend MAX_LIMIT (500).
export const TIMEFRAMES: TimeframeDef[] = [
  { key: '1m', label: '1м', bucketSec: 60, rawLimit: 240 },
  { key: '5m', label: '5м', bucketSec: 300, rawLimit: 300 },
  { key: '1h', label: '1ч', bucketSec: 3600, rawLimit: 480 },
  { key: '1d', label: '1д', bucketSec: 86400, rawLimit: 500 },
]

export function timeframeByKey(key: TimeframeKey): TimeframeDef {
  return TIMEFRAMES.find((t) => t.key === key) ?? TIMEFRAMES[0]
}

/**
 * Parse the backend's ISO-8601 LocalDateTime (no zone) as UTC seconds.
 * The oracle ships `time` without a timezone (Java LocalDateTime); the
 * original chart treated it as UTC via `+ 'Z'`, we keep that contract so
 * candle timestamps stay stable across the upgrade.
 */
export function candleTimeSec(c: OhlcvCandle): number {
  return Math.floor(Date.parse(c.time + 'Z') / 1000)
}

/**
 * Re-bucket ascending 1m candles into `bucketSec`-wide OHLCV bars.
 *
 * Aggregation rules (standard OHLCV roll-up):
 *   - open  = first (oldest) candle's open in the bucket
 *   - high  = max high
 *   - low   = min low
 *   - close = last (newest) candle's close in the bucket
 *   - volume = sum of volumes
 *   - swapCount = sum of swapCounts
 *
 * `time` of the bucket = the floor of the first candle's time to the
 * bucket boundary, re-emitted as an ISO string WITHOUT a trailing 'Z'
 * (so `candleTimeSec` round-trips it the same way the backend candles
 * are parsed). Input must be ascending by time; output is ascending too.
 *
 * Empty input → empty output (the empty-state path stays intact).
 */
export function aggregateCandles(
  candles: OhlcvCandle[],
  bucketSec: number,
): OhlcvCandle[] {
  if (bucketSec <= 60 || candles.length === 0) {
    // 1m is the native granularity — no work to do.
    return candles
  }

  const buckets = new Map<number, OhlcvCandle>()
  // Preserve insertion order = chronological order, since `candles` is
  // already ascending and we floor monotonically.
  for (const c of candles) {
    const t = candleTimeSec(c)
    const bucketStart = Math.floor(t / bucketSec) * bucketSec
    const existing = buckets.get(bucketStart)
    if (!existing) {
      buckets.set(bucketStart, {
        // Re-emit as a zone-less ISO string to match the parse contract.
        time: new Date(bucketStart * 1000).toISOString().replace('Z', ''),
        open: c.open,
        high: c.high,
        low: c.low,
        close: c.close,
        volume: c.volume,
        swapCount: c.swapCount,
      })
    } else {
      existing.high = Math.max(existing.high, c.high)
      existing.low = Math.min(existing.low, c.low)
      existing.close = c.close // candles ascending → last wins = newest close
      existing.volume += c.volume
      existing.swapCount += c.swapCount
    }
  }

  return Array.from(buckets.values()).sort(
    (a, b) => candleTimeSec(a) - candleTimeSec(b),
  )
}
