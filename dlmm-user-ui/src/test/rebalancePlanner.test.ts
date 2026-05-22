import { describe, it, expect } from 'vitest'
import { planRebalance, validateTargets } from '../lib/rebalancePlanner'

describe('planRebalance (F-07)', () => {
  it('returns empty plan for zero-portfolio', () => {
    const plan = planRebalance([], [{ symbol: 'SBER', targetPct: 100 }])
    expect(plan.hops).toEqual([])
    expect(plan.totalRubMoved).toBe(0)
  })

  it('emits no hops when already in tolerance', () => {
    const portfolio = [
      { tokenId: 'srub', symbol: 'SRUB', amount: 500_000, priceRub: 1 },
      { tokenId: 'sber', symbol: 'SBER', amount: 1000, priceRub: 500 },
    ]
    // 50/50 split, exactly the target.
    const plan = planRebalance(portfolio, [
      { symbol: 'SRUB', targetPct: 50 },
      { symbol: 'SBER', targetPct: 50 },
    ])
    expect(plan.hops).toEqual([])
    expect(plan.skipped.length).toBe(2)
  })

  it('emits a sell when one symbol is overweight', () => {
    const portfolio = [
      { tokenId: 'srub', symbol: 'SRUB', amount: 0, priceRub: 1 },
      { tokenId: 'sber', symbol: 'SBER', amount: 1000, priceRub: 1000 }, // 100%
    ]
    // Target 50/50 — must sell ~500 SBER to SRUB.
    const plan = planRebalance(portfolio, [
      { symbol: 'SRUB', targetPct: 50 },
      { symbol: 'SBER', targetPct: 50 },
    ])
    expect(plan.hops.length).toBe(1)
    expect(plan.hops[0].fromSymbol).toBe('SBER')
    expect(plan.hops[0].toSymbol).toBe('SRUB')
    expect(plan.hops[0].amountIn).toBe(500)
  })

  it('emits a buy when target includes a token user does not hold', () => {
    const portfolio = [
      { tokenId: 'srub', symbol: 'SRUB', amount: 1_000_000, priceRub: 1 },
    ]
    const plan = planRebalance(portfolio, [
      { symbol: 'SRUB', targetPct: 60 },
      { symbol: 'GAZP', targetPct: 40 }, // user holds 0, but we'd buy
    ])
    // Without a GAZP price in the portfolio view we should skip.
    expect(plan.hops).toEqual([])
    expect(plan.skipped.some((s) => s.symbol === 'GAZP')).toBe(true)
  })

  it('emits sell-then-buy for two-symbol rebalance', () => {
    const portfolio = [
      { tokenId: 'srub', symbol: 'SRUB', amount: 0, priceRub: 1 },
      { tokenId: 'sber', symbol: 'SBER', amount: 1000, priceRub: 500 },
      { tokenId: 'gazp', symbol: 'GAZP', amount: 0, priceRub: 200 },
    ]
    // Currently 100% SBER. Target: 50% SBER + 50% GAZP.
    const plan = planRebalance(portfolio, [
      { symbol: 'SBER', targetPct: 50 },
      { symbol: 'GAZP', targetPct: 50 },
    ])
    // Sell ~500 SBER → SRUB (pivot), then buy ~500K SRUB worth of GAZP.
    expect(plan.hops.length).toBe(2)
    expect(plan.hops[0].fromSymbol).toBe('SBER')
    expect(plan.hops[1].toSymbol).toBe('GAZP')
  })

  it('skips dust trades inside tolerance', () => {
    const portfolio = [
      { tokenId: 'srub', symbol: 'SRUB', amount: 500_000, priceRub: 1 },
      { tokenId: 'sber', symbol: 'SBER', amount: 1003, priceRub: 500 }, // ~50.15%
    ]
    // Target 50/50 — delta = 0.15%, below tolerance 0.5%.
    const plan = planRebalance(portfolio, [
      { symbol: 'SRUB', targetPct: 50 },
      { symbol: 'SBER', targetPct: 50 },
    ])
    expect(plan.hops).toEqual([])
  })
})

describe('validateTargets', () => {
  it('accepts a valid 100-sum target list', () => {
    expect(validateTargets([
      { symbol: 'SRUB', targetPct: 60 },
      { symbol: 'SBER', targetPct: 40 },
    ])).toBeNull()
  })

  it('rejects a list that does not sum to 100', () => {
    expect(validateTargets([
      { symbol: 'SRUB', targetPct: 60 },
      { symbol: 'SBER', targetPct: 30 },
    ])).toMatch(/Сумма/)
  })

  it('rejects negative targets', () => {
    expect(validateTargets([
      { symbol: 'SRUB', targetPct: -10 },
      { symbol: 'SBER', targetPct: 110 },
    ])).toMatch(/отрицательной/)
  })
})
