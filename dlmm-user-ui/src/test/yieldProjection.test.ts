import { describe, it, expect } from 'vitest'
import { projectFeeIncome, periodReturnPct } from '@/lib/yield'

describe('projectFeeIncome — current-APY fee projection', () => {
  it('a full year at the APY equals amount × apy', () => {
    expect(projectFeeIncome(100_000, 12, 365)).toBeCloseTo(12_000, 6)
  })

  it('pro-rates by days / 365', () => {
    expect(projectFeeIncome(100_000, 12, 30)).toBeCloseTo((100_000 * 0.12 * 30) / 365, 6)
  })

  it('scales linearly with the deposit', () => {
    expect(projectFeeIncome(50_000, 8, 90)).toBeCloseTo(projectFeeIncome(100_000, 8, 90) / 2, 6)
  })

  it('returns 0 (never NaN) for non-positive or non-finite inputs', () => {
    expect(projectFeeIncome(0, 12, 30)).toBe(0)
    expect(projectFeeIncome(100_000, 0, 30)).toBe(0)
    expect(projectFeeIncome(100_000, 12, 0)).toBe(0)
    expect(projectFeeIncome(-100, 12, 30)).toBe(0)
    expect(projectFeeIncome(100_000, -5, 30)).toBe(0)
    expect(projectFeeIncome(NaN, 12, 30)).toBe(0)
  })
})

describe('periodReturnPct', () => {
  it('is the APY scaled to the holding period', () => {
    expect(periodReturnPct(12, 365)).toBeCloseTo(12, 6)
    expect(periodReturnPct(12, 30)).toBeCloseTo((12 * 30) / 365, 6)
    expect(periodReturnPct(0, 30)).toBe(0)
  })
})
