import { describe, it, expect } from 'vitest'
import { estimateRemoveReturn, LP_EXIT_FEE_BPS } from '@/lib/removeEstimate'

const POS = {
  currentValueX: 1000,
  currentValueY: 2000,
  unclaimedFeeX: 5,
  unclaimedFeeY: 10,
}

describe('estimateRemoveReturn', () => {
  it('100%: full principal minus 0.1% exit fee, plus all fees', () => {
    const e = estimateRemoveReturn(POS, 100)
    // exit fee = 1000 * 10/10000 = 1 ; 2000 -> 2
    expect(e.exitFeeX).toBeCloseTo(1, 6)
    expect(e.exitFeeY).toBeCloseTo(2, 6)
    expect(e.principalX).toBeCloseTo(999, 6)
    expect(e.principalY).toBeCloseTo(1998, 6)
    expect(e.feeX).toBe(5)
    expect(e.feeY).toBe(10)
    expect(e.totalX).toBeCloseTo(1004, 6)
    expect(e.totalY).toBeCloseTo(2008, 6)
  })

  it('50%: principal scales with percent, exit fee scales too', () => {
    const e = estimateRemoveReturn(POS, 50)
    expect(e.principalX).toBeCloseTo(499.5, 6) // 500 - 0.5
    expect(e.principalY).toBeCloseTo(999, 6) // 1000 - 1
    expect(e.totalX).toBeCloseTo(504.5, 6)
    expect(e.totalY).toBeCloseTo(1009, 6)
  })

  it('claims the FULL accrued fees even at a tiny percent (Meteora behaviour)', () => {
    const e = estimateRemoveReturn(POS, 1)
    expect(e.feeX).toBe(5) // not 0.05
    expect(e.feeY).toBe(10)
  })

  it('clamps percent to 0..100', () => {
    expect(estimateRemoveReturn(POS, 150).principalX).toBeCloseTo(999, 6)
    expect(estimateRemoveReturn(POS, -10).totalX).toBe(5) // 0 principal + full fees
  })

  it('handles a fee-less / valueless position without NaN', () => {
    const e = estimateRemoveReturn({ currentValueX: 0, currentValueY: 0, unclaimedFeeX: 0, unclaimedFeeY: 0 }, 100)
    expect(e.totalX).toBe(0)
    expect(e.totalY).toBe(0)
  })

  it('exposes the default exit-fee bps the UI labels', () => {
    expect(LP_EXIT_FEE_BPS).toBe(10)
  })
})
