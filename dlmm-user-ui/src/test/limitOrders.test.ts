import { describe, it, expect } from 'vitest'
import { scaleLimitOrder, AMOUNT_SCALE } from '@/api/scale'
import type { LimitOrder } from '@/api/types'

const rawOrder: LimitOrder = {
  id: 'o1', poolId: 'p1', tokenInId: 'x', tokenOutId: 'y',
  tokenInSymbol: 'SBTC', tokenOutSymbol: 'SRUB', side: 'SELL',
  amountIn: 5_000, limitPrice: 5_300_000, amountOut: 26_500_000_000,
  status: 'OPEN', createdAt: '2026-05-31T00:00:00', filledAt: null, cancelledAt: null,
}

describe('scaleLimitOrder', () => {
  it('scales amountIn/amountOut by 1/AMOUNT_SCALE but leaves limitPrice (a Y/X ratio) untouched', () => {
    const o = scaleLimitOrder(rawOrder)
    expect(o.amountIn).toBe(5_000 / AMOUNT_SCALE) // 0.5 SBTC
    expect(o.amountOut).toBe(26_500_000_000 / AMOUNT_SCALE) // 2 650 000 SRUB
    expect(o.limitPrice).toBe(5_300_000) // ratio — NOT scaled
  })

  it('preserves side/status/symbols and is consistent with the SELL fill identity out=in·price', () => {
    const o = scaleLimitOrder(rawOrder)
    expect(o.side).toBe('SELL')
    expect(o.status).toBe('OPEN')
    // human-unit SELL identity: amountOut == amountIn * limitPrice
    expect(o.amountOut).toBeCloseTo(o.amountIn * o.limitPrice, 6)
  })
})
