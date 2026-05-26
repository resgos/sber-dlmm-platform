import { describe, it, expect } from 'vitest'
import { calculateHealth, bandColor } from '../lib/positionHealth'
import type { Position, Pool, LiquidityStrategy, PoolStatus } from '../api/types'

function mkPosition(over: Partial<Position> = {}): Position {
  return {
    id: 'pos-1',
    poolId: 'pool-1',
    tokenXSymbol: 'SRUB',
    tokenYSymbol: 'SBER',
    binRangeMin: 100,
    binRangeMax: 110,
    strategy: 'SPOT' as LiquidityStrategy,
    totalLiquidityShares: 1000,
    unclaimedFeeX: 0,
    unclaimedFeeY: 0,
    currentValueX: 50000,
    currentValueY: 100,
    initialDepositX: 50000,
    initialDepositY: 100,
    isActive: true,
    createdAt: new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString(),
    closedAt: null,
    ...over,
  }
}

function mkPool(over: Partial<Pool> = {}): Pool {
  return {
    id: 'pool-1',
    tokenXId: 'tok-srub',
    tokenYId: 'tok-sber',
    tokenXSymbol: 'SRUB',
    tokenYSymbol: 'SBER',
    binStep: 25,
    baseFeeBps: 30,
    activeBinId: 105,
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

describe('calculateHealth (new feature)', () => {
  it('returns 0 range-fit when activeBin is below the position range', () => {
    const h = calculateHealth(
      mkPosition({ binRangeMin: 200, binRangeMax: 210 }),
      mkPool({ activeBinId: 100 }),
    )
    expect(h.factors.rangeFit.contribution).toBe(0)
    expect(h.factors.rangeFit.reason).toMatch(/вне диапазона/)
  })

  it('returns 0 range-fit when activeBin is above the position range', () => {
    const h = calculateHealth(
      mkPosition({ binRangeMin: 100, binRangeMax: 110 }),
      mkPool({ activeBinId: 200 }),
    )
    expect(h.factors.rangeFit.contribution).toBe(0)
  })

  it('returns maximum range-fit when activeBin is centred in range', () => {
    const h = calculateHealth(
      mkPosition({ binRangeMin: 100, binRangeMax: 120 }),
      mkPool({ activeBinId: 110 }), // centre = 110
    )
    // 45% weight × 1.0 = 45 contribution
    expect(h.factors.rangeFit.contribution).toBe(45)
  })

  it('reduces range-fit slightly when activeBin is at the edge of range', () => {
    const h = calculateHealth(
      mkPosition({ binRangeMin: 100, binRangeMax: 120 }),
      mkPool({ activeBinId: 120 }), // at the edge
    )
    // Range fit raw = 1 - 0.15 * 1 = 0.85; weighted = 45 * 0.85 = ~38
    expect(h.factors.rangeFit.contribution).toBeGreaterThan(30)
    expect(h.factors.rangeFit.contribution).toBeLessThan(45)
  })

  it('handles single-bin position without divide-by-zero', () => {
    const h = calculateHealth(
      mkPosition({ binRangeMin: 100, binRangeMax: 100 }),
      mkPool({ activeBinId: 100 }),
    )
    expect(h.factors.rangeFit.contribution).toBe(45)
  })

  it('fee-earning contribution = 0 when no fees accrued yet', () => {
    const h = calculateHealth(
      mkPosition({ unclaimedFeeX: 0, unclaimedFeeY: 0 }),
      mkPool(),
    )
    expect(h.factors.feeEarning.contribution).toBe(0)
  })

  it('fee-earning at target APY scores full weight (35)', () => {
    // 30-day-old position, initial 100k, fees this period that
    // annualise to 20% APY → 100k * 0.20 / (365/30) ≈ 1643 unclaimed
    const initial = 100_000
    const ageMs = 30 * 24 * 60 * 60 * 1000
    const targetAnnualisedFees = initial * 0.20 // 20% APY
    const periodFees = targetAnnualisedFees * (ageMs / (365.25 * 24 * 60 * 60 * 1000))
    const h = calculateHealth(
      mkPosition({
        initialDepositX: initial,
        initialDepositY: 0,
        unclaimedFeeX: Math.floor(periodFees),
        unclaimedFeeY: 0,
        createdAt: new Date(Date.now() - ageMs).toISOString(),
      }),
      mkPool(),
    )
    // Should be very close to full weight (35), allow ±1 for rounding.
    expect(h.factors.feeEarning.contribution).toBeGreaterThanOrEqual(34)
    expect(h.factors.feeEarning.contribution).toBeLessThanOrEqual(35)
  })

  it('fee-earning is clamped at full weight when annualised APY exceeds the target', () => {
    const h = calculateHealth(
      mkPosition({
        initialDepositX: 100_000,
        initialDepositY: 0,
        unclaimedFeeX: 1_000_000, // crazy high
        createdAt: new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString(),
      }),
      mkPool(),
    )
    expect(h.factors.feeEarning.contribution).toBe(35)
  })

  it('age contribution scales linearly between 1 and 7 days', () => {
    const ageDays = 4
    const h = calculateHealth(
      mkPosition({ createdAt: new Date(Date.now() - ageDays * 24 * 60 * 60 * 1000).toISOString() }),
      mkPool(),
    )
    // Raw = 0.5 + (4-1)/(7-1)*0.2 = 0.6 → weighted = 20 * 0.6 = 12
    expect(h.factors.age.contribution).toBe(12)
  })

  it('age contribution reaches full weight (20) at 30+ days', () => {
    const h = calculateHealth(
      mkPosition({ createdAt: new Date(Date.now() - 45 * 24 * 60 * 60 * 1000).toISOString() }),
      mkPool(),
    )
    expect(h.factors.age.contribution).toBe(20)
  })

  it('returns the right band for each score range', () => {
    // Excellent: 80+
    const excellent = calculateHealth(
      mkPosition({
        initialDepositX: 100_000,
        unclaimedFeeX: 999_999,
        createdAt: new Date(Date.now() - 60 * 24 * 60 * 60 * 1000).toISOString(),
        binRangeMin: 100,
        binRangeMax: 120,
      }),
      mkPool({ activeBinId: 110 }),
    )
    expect(excellent.band).toBe('excellent')

    // Poor: out of range + young + no fees
    const poor = calculateHealth(
      mkPosition({
        unclaimedFeeX: 0, unclaimedFeeY: 0,
        binRangeMin: 100, binRangeMax: 110,
        createdAt: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
      }),
      mkPool({ activeBinId: 500 }),
    )
    expect(poor.band).toBe('poor')
  })

  it('handles missing pool gracefully (range fit = 0, others still computed)', () => {
    const h = calculateHealth(mkPosition(), undefined)
    expect(h.factors.rangeFit.contribution).toBe(0)
    expect(h.factors.rangeFit.reason).toMatch(/Нет данных о пуле/)
    expect(h.total).toBeGreaterThanOrEqual(0)
  })
})

describe('bandColor', () => {
  it('maps each band to a CSS variable', () => {
    expect(bandColor('excellent')).toMatch(/sber-green/)
    expect(bandColor('good')).toMatch(/sber-green-light/)
    expect(bandColor('fair')).toMatch(/sber-amber/)
    expect(bandColor('poor')).toMatch(/plasma-critical/)
  })
})

// NEW-4 (Batch #3, 2026-05-26) — per-pool target APY override.
describe('calculateHealth — NEW-4 targetApy override', () => {
  /** Position that earns ~10% APY annualised: 100k initial, 30 days, fees that
   *  annualise to 10k (= 10% of initial). */
  function tenPctApyPosition() {
    const initial = 100_000
    const ageMs = 30 * 24 * 60 * 60 * 1000
    const targetAnnualisedFees = initial * 0.10 // 10% APY
    const periodFees = targetAnnualisedFees * (ageMs / (365.25 * 24 * 60 * 60 * 1000))
    return mkPosition({
      initialDepositX: initial,
      initialDepositY: 0,
      unclaimedFeeX: Math.floor(periodFees),
      unclaimedFeeY: 0,
      createdAt: new Date(Date.now() - ageMs).toISOString(),
    })
  }

  it('against the default 20% target a 10% APY scores ~half of the fee weight', () => {
    const h = calculateHealth(tenPctApyPosition(), mkPool())
    // 10/20 = 0.5; weighted = 0.35 * 0.5 * 100 = 17.5 → rounded 17 or 18
    expect(h.factors.feeEarning.contribution).toBeGreaterThanOrEqual(17)
    expect(h.factors.feeEarning.contribution).toBeLessThanOrEqual(18)
  })

  it('against a per-pool target of 10% the same position scores full fee weight', () => {
    const h = calculateHealth(tenPctApyPosition(), mkPool(), { targetApy: 10 })
    // 10/10 clamped to 1.0 → weighted 35
    expect(h.factors.feeEarning.contribution).toBeGreaterThanOrEqual(34)
    expect(h.factors.feeEarning.contribution).toBeLessThanOrEqual(35)
  })

  it('reports the per-pool target APY in the tooltip reason', () => {
    const h = calculateHealth(tenPctApyPosition(), mkPool(), { targetApy: 12.5 })
    expect(h.factors.feeEarning.reason).toMatch(/цель 12\.5%/)
  })

  it('ignores non-positive, NaN, and infinite targetApy values (falls back to 20%)', () => {
    const baseline = calculateHealth(tenPctApyPosition(), mkPool())
    for (const bad of [0, -5, Number.NaN, Number.POSITIVE_INFINITY]) {
      const h = calculateHealth(tenPctApyPosition(), mkPool(), { targetApy: bad })
      expect(h.factors.feeEarning.contribution).toBe(baseline.factors.feeEarning.contribution)
    }
  })

  it('formats whole-number targets without a trailing .0', () => {
    const h = calculateHealth(tenPctApyPosition(), mkPool(), { targetApy: 8 })
    expect(h.factors.feeEarning.reason).toMatch(/цель 8%/)
    expect(h.factors.feeEarning.reason).not.toMatch(/цель 8\.0%/)
  })
})
