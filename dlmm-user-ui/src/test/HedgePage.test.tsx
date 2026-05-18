import { describe, it, expect } from 'vitest'
import { hedgeAmountFromRatio, effectiveHedgeRate } from '../pages/HedgePage'

/**
 * Sprint 4 #4.1 — pure hedge-calculation helpers, pulled out of HedgePage
 * so they can be locked down without spinning up jsdom + AntD + react-query.
 *
 * Rendering tests for the page itself are intentionally omitted in this
 * sprint — react-query + auth wrapper makes for noisy setup that mostly
 * checks AntD itself. The shape-level guard tests live in
 * apiTypes.test.ts. Page-render coverage is Sprint 5 backlog.
 */

describe('hedgeAmountFromRatio', () => {
  it('returns 25% of balance for 0.25 ratio, floored', () => {
    expect(hedgeAmountFromRatio(0.25, 1_000_000)).toBe(250_000)
  })

  it('returns full balance for 1.0 ratio', () => {
    expect(hedgeAmountFromRatio(1.0, 5_000_000)).toBe(5_000_000)
  })

  it('caps ratio at 1.0 — over-100% requests never recommend more than holding', () => {
    expect(hedgeAmountFromRatio(1.5, 1_000_000)).toBe(1_000_000)
  })

  it('returns 0 for zero or negative balance', () => {
    expect(hedgeAmountFromRatio(0.5, 0)).toBe(0)
    expect(hedgeAmountFromRatio(0.5, -100)).toBe(0)
  })

  it('returns 0 for zero or negative ratio', () => {
    expect(hedgeAmountFromRatio(0, 1_000_000)).toBe(0)
    expect(hedgeAmountFromRatio(-0.5, 1_000_000)).toBe(0)
  })

  it('returns 0 for non-finite inputs', () => {
    expect(hedgeAmountFromRatio(NaN, 1_000_000)).toBe(0)
    expect(hedgeAmountFromRatio(0.5, NaN)).toBe(0)
    expect(hedgeAmountFromRatio(0.5, Infinity)).toBe(0)
  })

  it('floors fractional results — no fractional kopecks recommended', () => {
    // 1_234_567 × 0.33 = 407_407.11 → 407_407
    expect(hedgeAmountFromRatio(0.33, 1_234_567)).toBe(407_407)
  })
})

describe('effectiveHedgeRate', () => {
  it('computes rate as out/in', () => {
    expect(effectiveHedgeRate(100, 1.25)).toBeCloseTo(0.0125, 6)
  })

  it('returns 0 when amountIn is zero or negative', () => {
    expect(effectiveHedgeRate(0, 100)).toBe(0)
    expect(effectiveHedgeRate(-1, 100)).toBe(0)
  })

  it('reasonable RUB→USD example: 100k SRUB → ~1099 SUSD ≈ 0.01099 rate', () => {
    expect(effectiveHedgeRate(100_000, 1099)).toBeCloseTo(0.01099, 5)
  })
})
