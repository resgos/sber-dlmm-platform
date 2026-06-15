import { describe, it, expect } from 'vitest'
import { swapVolumeSrub } from '@/lib/swapVolume'
import type { Transaction } from '@/api/types'

/**
 * Pins the admin "Объём свопов 24ч" KPI semantics (fix fbb999a): turnover is
 * the SRUB leg of each swap, NOT a sum of raw amountIn across mixed token
 * units (which rendered a meaningless "1").
 */
const tx = (p: Partial<Transaction>): Transaction =>
  ({
    id: 'x', txType: 'SWAP', status: 'CONFIRMED', userId: 'u', poolId: 'p',
    tokenInId: null, amountIn: null, tokenOutId: null, amountOut: null,
    feeAmount: null, feeRate: null, binsCrossed: null, idempotencyKey: null,
    metadata: null, errorMessage: null, createdAt: '2026-06-12T10:00:00',
    ...p,
  }) as Transaction

describe('swapVolumeSrub — quote-leg turnover', () => {
  it('buys (out=SRUB) add amountOut, sells (in=SRUB) add amountIn', () => {
    const v = swapVolumeSrub([
      tx({ tokenInSymbol: 'SETH', tokenOutSymbol: 'SRUB', amountIn: 0.5, amountOut: 59700 }),
      tx({ tokenInSymbol: 'SRUB', tokenOutSymbol: 'SUSDT', amountIn: 23900, amountOut: 320 }),
    ])
    expect(v).toBe(59700 + 23900)
  })

  it('ignores non-SWAP rows', () => {
    const v = swapVolumeSrub([
      tx({ txType: 'ADD_LIQUIDITY', tokenInSymbol: 'SRUB', amountIn: 1_000_000 }),
      tx({ txType: 'MINT', tokenOutSymbol: 'SRUB', amountOut: 500 }),
      tx({ tokenOutSymbol: 'SRUB', amountOut: 100 }),
    ])
    expect(v).toBe(100)
  })

  it('skips non-SRUB pairs instead of summing foreign units', () => {
    // The exact bug: a hypothetical SETH/SUSDT swap must NOT contribute its
    // raw SETH/SUSDT amount to a SRUB total.
    const v = swapVolumeSrub([
      tx({ tokenInSymbol: 'SETH', tokenOutSymbol: 'SUSDT', amountIn: 999, amountOut: 999 }),
      tx({ tokenInSymbol: 'SRUB', tokenOutSymbol: 'SETH', amountIn: 7000 }),
    ])
    expect(v).toBe(7000)
  })

  it('treats null amounts as 0 and empty input as 0', () => {
    expect(swapVolumeSrub([])).toBe(0)
    expect(swapVolumeSrub([tx({ tokenOutSymbol: 'SRUB', amountOut: null })])).toBe(0)
  })
})
