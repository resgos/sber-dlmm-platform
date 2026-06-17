import { describe, it, expect } from 'vitest'
import { classifyFeeRate } from '../pages/TransactionsPage'

// 2026-06-17 — elevated-fee indicator on the transaction feed. The pure
// classifier is the heart of the feature; values mirror real demo data
// (psql): base 25 bps pool swaps at 125 bps (×5 → high) and a base 20 bps
// pool swap at 50 bps (×2.5 → elevated), vs the 7–30 bps ordinary swaps.
describe('classifyFeeRate (transaction feed)', () => {
  it('flags a ×5 swap as high (real c0..001 demo swap: 125 vs 25 bps)', () => {
    expect(classifyFeeRate(125, 25)).toBe('high')
  })

  it('flags a ×2.5 swap as elevated (real c0..003 demo swap: 50 vs 20 bps)', () => {
    expect(classifyFeeRate(50, 20)).toBe('elevated')
  })

  it('treats fee at/just-above base as normal (no noise badge)', () => {
    expect(classifyFeeRate(25, 25)).toBe('normal') // exactly base
    expect(classifyFeeRate(30, 25)).toBe('normal') // ×1.2 — within noise band
    expect(classifyFeeRate(7.4, 20)).toBe('normal') // floored below base
  })

  it('honours the ×1.5 / ×3 thresholds at the boundary', () => {
    expect(classifyFeeRate(37.5, 25)).toBe('elevated') // exactly ×1.5
    expect(classifyFeeRate(37.4, 25)).toBe('normal') // just under ×1.5
    expect(classifyFeeRate(75, 25)).toBe('high') // exactly ×3
    expect(classifyFeeRate(74.9, 25)).toBe('elevated') // just under ×3
  })

  it('fails open (normal) on missing or non-positive inputs', () => {
    expect(classifyFeeRate(null, 25)).toBe('normal')
    expect(classifyFeeRate(undefined, 25)).toBe('normal')
    expect(classifyFeeRate(0, 25)).toBe('normal')
    expect(classifyFeeRate(125, null)).toBe('normal') // pool not in fetched list
    expect(classifyFeeRate(125, undefined)).toBe('normal')
    expect(classifyFeeRate(125, 0)).toBe('normal') // guards divide-by-zero
    expect(classifyFeeRate(-5, 25)).toBe('normal')
  })
})
