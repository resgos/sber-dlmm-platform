import { describe, it, expect } from 'vitest'
import { positionValueQuote, portfolioValueQuote } from '../lib/positionValue'
import type { Position, Pool } from '@/api/types'

const pos = (poolId: string, x: number, y: number) =>
  ({ poolId, currentValueX: x, currentValueY: y } as unknown as Position)
const pool = (id: string, price: number) => ({ id, currentPrice: price } as unknown as Pool)

describe('positionValueQuote / portfolioValueQuote', () => {
  it('values a position as y + x*price (quote/SRUB units)', () => {
    // 2 SBTC @ 5,000,000 ₽ + 1,000,000 ₽ on the Y leg = 11,000,000 ₽
    expect(positionValueQuote(pos('p', 2, 1_000_000), pool('p', 5_000_000))).toBe(11_000_000)
  })

  it('ignores the X leg when the pool (price) is missing — no NaN', () => {
    expect(positionValueQuote(pos('p', 2, 500), undefined)).toBe(500)
  })

  it('treats null/zero legs as 0', () => {
    expect(positionValueQuote({ currentValueX: 0, currentValueY: 0 } as Position, pool('p', 100))).toBe(0)
    expect(positionValueQuote({} as Position, pool('p', 100))).toBe(0)
  })

  it('sums the portfolio across pools at each pool price', () => {
    const positions = [
      pos('a', 1, 100), // 1*200 + 100 = 300
      pos('b', 2, 50), // 2*10 + 50 = 70
      pos('c', 5, 0), // pool c missing → price 0 → 0
    ]
    const poolById = new Map<string, Pool>([
      ['a', pool('a', 200)],
      ['b', pool('b', 10)],
    ])
    expect(portfolioValueQuote(positions, poolById)).toBe(370)
  })

  it('empty book is 0', () => {
    expect(portfolioValueQuote([], new Map())).toBe(0)
  })
})
