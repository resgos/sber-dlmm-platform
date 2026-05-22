import { describe, it, expect } from 'vitest'
import { computeProMetrics } from '../lib/poolMetrics'
import type { Pool, PoolStatus } from '../api/types'

function mkPool(over: Partial<Pool> = {}): Pool {
  return {
    id: 'pool-test-1',
    tokenXId: 'tok-srub',
    tokenYId: 'tok-sber',
    tokenXSymbol: 'SRUB',
    tokenYSymbol: 'SBER',
    binStep: 25,
    baseFeeBps: 30,
    activeBinId: 100,
    currentPrice: 500,
    totalTvlX: 1_000_000,
    totalTvlY: 2000,
    volume24h: 100_000,
    estimatedApy: 18,
    status: 'ACTIVE' as PoolStatus,
    createdAt: '2026-05-01T00:00:00Z',
    ...over,
  }
}

describe('computeProMetrics (G-23)', () => {
  it('returns positive numeric metrics for typical pool', () => {
    const m = computeProMetrics(mkPool())
    expect(m.volatilityPct30d).toBeGreaterThan(0)
    expect(m.maxDrawdownPct).toBeGreaterThanOrEqual(0)
    expect(typeof m.sharpe).toBe('number')
    expect(m.isSynthetic).toBe(true)
  })

  it('produces stable metrics across calls (deterministic per pool id)', () => {
    const a = computeProMetrics(mkPool({ id: 'stable-pool-id' }))
    const b = computeProMetrics(mkPool({ id: 'stable-pool-id' }))
    expect(a.volatilityPct30d).toBe(b.volatilityPct30d)
    expect(a.maxDrawdownPct).toBe(b.maxDrawdownPct)
    expect(a.sharpe).toBe(b.sharpe)
  })

  it('different pools produce different metrics', () => {
    const a = computeProMetrics(mkPool({ id: 'pool-aaa' }))
    const b = computeProMetrics(mkPool({ id: 'pool-bbb' }))
    // Не обязательно vol другая, но что-то одно из 3 — точно.
    const sameAll = a.volatilityPct30d === b.volatilityPct30d &&
                    a.maxDrawdownPct === b.maxDrawdownPct &&
                    a.sharpe === b.sharpe
    expect(sameAll).toBe(false)
  })

  it('higher APY pool gets higher synthetic volatility (correlates by design)', () => {
    const low = computeProMetrics(mkPool({ id: 'low-apy', estimatedApy: 5 }))
    const high = computeProMetrics(mkPool({ id: 'high-apy', estimatedApy: 50 }))
    expect(high.volatilityPct30d).toBeGreaterThan(low.volatilityPct30d)
  })

  it('positive Sharpe when APY > risk-free (14%)', () => {
    const m = computeProMetrics(mkPool({ estimatedApy: 30 }))
    expect(m.sharpe).toBeGreaterThan(0)
  })

  it('negative Sharpe when APY < risk-free', () => {
    const m = computeProMetrics(mkPool({ estimatedApy: 8 }))
    expect(m.sharpe).toBeLessThan(0)
  })

  it('max drawdown is bounded [0, 100]', () => {
    const m = computeProMetrics(mkPool())
    expect(m.maxDrawdownPct).toBeGreaterThanOrEqual(0)
    expect(m.maxDrawdownPct).toBeLessThan(100)
  })
})
