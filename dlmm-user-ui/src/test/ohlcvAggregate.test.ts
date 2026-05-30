import { describe, it, expect } from 'vitest'
import {
  aggregateCandles,
  connectCandles,
  candleTimeSec,
  timeframeByKey,
  TIMEFRAMES,
} from '../lib/ohlcv'
import type { OhlcvCandle } from '@/api/types'

// PC-02 (2026-05-29) — pin the client-side OHLCV roll-up that powers the
// 5м/1ч/1д timeframes. The contract under test:
//   - it is a LOSSLESS roll-up of the real 1m candles (no fabricated bars)
//   - standard OHLCV bucketing (open=first, high=max, low=min, close=last,
//     volume/swapCount summed)
//   - bucket boundaries floor to the interval; output stays ascending

/** Build a 1m candle anchored at a UTC epoch-second (zone-less ISO). */
function candle(epochSec: number, o: number, h: number, l: number, c: number, vol: number, swaps = 1): OhlcvCandle {
  return {
    // Match the backend's zone-less LocalDateTime shape so candleTimeSec
    // round-trips it identically (it appends 'Z' to parse as UTC).
    time: new Date(epochSec * 1000).toISOString().replace('Z', ''),
    open: o,
    high: h,
    low: l,
    close: c,
    volume: vol,
    swapCount: swaps,
  }
}

const MIN = 60

describe('aggregateCandles', () => {
  it('returns input untouched for 1m bucket (native granularity)', () => {
    const input = [candle(0, 1, 2, 0.5, 1.5, 10)]
    expect(aggregateCandles(input, 60)).toBe(input)
  })

  it('returns empty for empty input (preserves the empty-state path)', () => {
    expect(aggregateCandles([], 300)).toEqual([])
  })

  it('rolls five 1m candles into one 5m bar with correct OHLCV', () => {
    // Five consecutive 1m candles inside the same 5m window [0, 300).
    const input = [
      candle(0 * MIN, 100, 110, 95, 105, 10),
      candle(1 * MIN, 105, 120, 104, 108, 20),
      candle(2 * MIN, 108, 109, 90, 92, 5),
      candle(3 * MIN, 92, 130, 91, 125, 30), // contains the period high
      candle(4 * MIN, 125, 126, 88, 100, 15), // contains the period low
    ]
    const out = aggregateCandles(input, 300)
    expect(out).toHaveLength(1)
    const bar = out[0]
    expect(bar.open).toBe(100) // first candle's open
    expect(bar.close).toBe(100) // last candle's close
    expect(bar.high).toBe(130) // max high
    expect(bar.low).toBe(88) // min low
    expect(bar.volume).toBe(80) // 10+20+5+30+15
    expect(bar.swapCount).toBe(5)
    // bucket time floors to the 5m boundary = epoch 0
    expect(candleTimeSec(bar)).toBe(0)
  })

  it('splits candles across bucket boundaries (no fabricated in-between bars)', () => {
    // One candle in [0,300), one in [300,600), and a 10-minute GAP before
    // the third in [900,1200). Result must be exactly 3 bars — we do NOT
    // invent an empty bar for the missing [600,900) window.
    const input = [
      candle(1 * MIN, 10, 11, 9, 10.5, 1),
      candle(6 * MIN, 12, 13, 11, 12.5, 2),
      candle(16 * MIN, 20, 21, 19, 20.5, 3),
    ]
    const out = aggregateCandles(input, 300)
    expect(out).toHaveLength(3)
    expect(out.map((c) => candleTimeSec(c))).toEqual([0, 300, 900])
    // total volume conserved across the roll-up (lossless)
    expect(out.reduce((s, c) => s + c.volume, 0)).toBe(6)
  })

  it('keeps output ascending by time even if buckets fill out of order', () => {
    const out = aggregateCandles(
      [
        candle(7 * MIN, 5, 5, 5, 5, 1), // second bucket
        candle(1 * MIN, 1, 1, 1, 1, 1), // first bucket
      ],
      300,
    )
    const times = out.map((c) => candleTimeSec(c))
    expect(times).toEqual([...times].sort((a, b) => a - b))
  })

  it('aggregates a full hour of 1m candles into one 1h bar', () => {
    const input = Array.from({ length: 60 }, (_, i) =>
      candle(i * MIN, 100 + i, 100 + i + 1, 100 + i - 1, 100 + i, 1),
    )
    const out = aggregateCandles(input, 3600)
    expect(out).toHaveLength(1)
    expect(out[0].open).toBe(100)
    expect(out[0].close).toBe(159)
    expect(out[0].volume).toBe(60)
    expect(out[0].swapCount).toBe(60)
  })
})

describe('timeframe table', () => {
  it('exposes 1м/5м/1ч/1д mapped to 60/300/3600/86400 seconds', () => {
    expect(TIMEFRAMES.map((t) => t.bucketSec)).toEqual([60, 300, 3600, 86400])
    expect(TIMEFRAMES.map((t) => t.label)).toEqual(['1м', '5м', '1ч', '1д'])
  })

  it('falls back to 1m for an unknown key', () => {
    // @ts-expect-error — intentionally bad key to prove the guard.
    expect(timeframeByKey('bogus').key).toBe('1m')
  })

  it('never requests more raw candles than the backend MAX_LIMIT (500)', () => {
    for (const tf of TIMEFRAMES) {
      expect(tf.rawLimit).toBeLessThanOrEqual(500)
    }
  })
})

describe('connectCandles', () => {
  it('returns < 2 candles untouched', () => {
    expect(connectCandles([])).toEqual([])
    const one = [candle(0, 5, 5, 5, 5, 1)]
    expect(connectCandles(one)).toBe(one)
  })

  it('turns flat one-price candles into connected green/red bodies', () => {
    const flat = [
      candle(0 * MIN, 100, 100, 100, 100, 1),
      candle(1 * MIN, 110, 110, 110, 110, 1),
      candle(2 * MIN, 105, 105, 105, 105, 1),
    ]
    const out = connectCandles(flat)
    expect(out).toHaveLength(3)
    expect(out[0]).toBe(flat[0]) // first candle unchanged (no prior close)
    // 2nd: open = prev close (100), close 110 → green body spanning [100,110]
    expect(out[1].open).toBe(100)
    expect(out[1].close).toBe(110)
    expect(out[1].high).toBe(110)
    expect(out[1].low).toBe(100)
    // 3rd: open = prev close (110), close 105 → red body spanning [105,110]
    expect(out[2].open).toBe(110)
    expect(out[2].close).toBe(105)
    expect(out[2].high).toBe(110)
    expect(out[2].low).toBe(105)
  })

  it('preserves closes/volumes/timestamps and keeps real intra-candle wicks', () => {
    const input = [
      candle(0 * MIN, 10, 10, 10, 10, 7, 2),
      candle(1 * MIN, 20, 22, 18, 21, 9, 3),
    ]
    const out = connectCandles(input)
    expect(out.map((c) => c.close)).toEqual([10, 21])
    expect(out.map((c) => c.volume)).toEqual([7, 9])
    expect(out.map((c) => c.swapCount)).toEqual([2, 3])
    expect(out.map((c) => candleTimeSec(c))).toEqual([0, MIN])
    expect(out[1].open).toBe(10)  // = prev close
    expect(out[1].high).toBe(22)  // real high kept as upper wick
    expect(out[1].low).toBe(10)   // body extends down to prev close
  })
})
