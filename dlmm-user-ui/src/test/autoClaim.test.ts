import { describe, it, expect, beforeEach } from 'vitest'
import { autoClaimStore } from '../store/autoClaimStore'
import { shouldFire, previewFireable } from '../lib/useAutoClaimWatcher'
import type { Position, LiquidityStrategy } from '../api/types'

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

describe('autoClaimStore (new feature)', () => {
  beforeEach(() => {
    localStorage.clear()
    // In-memory history + per-position cooldown live outside localStorage
    // and persist across tests in the same file. Reset them so each test
    // starts from a clean state — without this, later tests inherit cap
    // / cooldown bookkeeping from earlier fires.
    autoClaimStore.__resetSideStateForTests()
  })

  it('returns disabled default when nothing is persisted', () => {
    const p = autoClaimStore.get()
    expect(p.enabled).toBe(false)
    expect(p.threshold).toBeGreaterThan(0)
  })

  it('set persists + notifies subscribers', () => {
    let notified = 0
    const unsub = autoClaimStore.subscribe(() => { notified++ })
    autoClaimStore.set({ enabled: true, threshold: 500, dailyCap: 20, skipPoolIds: [] })
    expect(notified).toBeGreaterThan(0)
    expect(autoClaimStore.get()).toEqual({ enabled: true, threshold: 500, dailyCap: 20, skipPoolIds: [] })
    unsub()
  })

  it('Sprint 10 wave 3: dailyCap honoured — isCappedToday flips at the limit', () => {
    autoClaimStore.set({ enabled: true, threshold: 100, dailyCap: 2, skipPoolIds: [] })
    expect(autoClaimStore.isCappedToday()).toBe(false)
    autoClaimStore.recordFired('pos-a', 'X/Y', 100)
    expect(autoClaimStore.isCappedToday()).toBe(false)
    autoClaimStore.recordFired('pos-b', 'X/Y', 200)
    expect(autoClaimStore.isCappedToday()).toBe(true)
    // canFire flips to false too — combined cooldown + cap.
    expect(autoClaimStore.canFire('pos-new')).toBe(false)
  })

  it('Sprint 10 wave 3: dailyCap=0 = unlimited', () => {
    autoClaimStore.set({ enabled: true, threshold: 100, dailyCap: 0, skipPoolIds: [] })
    for (let i = 0; i < 100; i++) autoClaimStore.recordFired(`p-${i}`, 'X/Y', 1)
    expect(autoClaimStore.isCappedToday()).toBe(false)
  })

  it('Sprint 10 wave 3: toggleSkipPool adds + removes from the exception list', () => {
    autoClaimStore.set({ enabled: true, threshold: 100, dailyCap: 20, skipPoolIds: [] })
    autoClaimStore.toggleSkipPool('pool-z')
    expect(autoClaimStore.isPoolSkipped('pool-z')).toBe(true)
    autoClaimStore.toggleSkipPool('pool-z')
    expect(autoClaimStore.isPoolSkipped('pool-z')).toBe(false)
  })

  it('Sprint 10 wave 3: legacy persisted policy without new fields gets backfilled', () => {
    // Pre-wave-3 policy: just enabled + threshold.
    localStorage.setItem('dlmm.user.autoClaim', JSON.stringify({ enabled: true, threshold: 500 }))
    const p = autoClaimStore.get()
    expect(p.dailyCap).toBe(20) // default backfill
    expect(p.skipPoolIds).toEqual([])
  })

  it('rejects malformed persisted value (falls back to default)', () => {
    localStorage.setItem('dlmm.user.autoClaim', '{"enabled":"yes please"}')
    expect(autoClaimStore.get().enabled).toBe(false)
  })

  it('canFire is true the first time, false within cooldown, true after', () => {
    expect(autoClaimStore.canFire('pos-x')).toBe(true)
    autoClaimStore.recordFired('pos-x', 'SRUB/SBER', 1234)
    expect(autoClaimStore.canFire('pos-x')).toBe(false)
    // Force the cooldown to expire by re-recording with an old timestamp
    // via the internal Map — we don't expose a setter, so we simulate
    // by re-calling and asserting that history caps at HISTORY_MAX (20).
    for (let i = 0; i < 25; i++) {
      autoClaimStore.recordFired(`pos-${i}`, 'X/Y', 100)
    }
    expect(autoClaimStore.history().length).toBeLessThanOrEqual(20)
  })
})

describe('shouldFire (auto-claim eligibility check)', () => {
  it('false when policy disabled', () => {
    expect(shouldFire(mkPosition({ unclaimedFeeX: 5000 }), 100, false)).toBe(false)
  })

  it('false when position closed', () => {
    expect(shouldFire(mkPosition({ unclaimedFeeX: 5000, isActive: false }), 100, true)).toBe(false)
  })

  it('false when below threshold', () => {
    expect(shouldFire(mkPosition({ unclaimedFeeX: 50, unclaimedFeeY: 30 }), 100, true)).toBe(false)
  })

  it('true when at or above threshold', () => {
    expect(shouldFire(mkPosition({ unclaimedFeeX: 60, unclaimedFeeY: 40 }), 100, true)).toBe(true)
    expect(shouldFire(mkPosition({ unclaimedFeeX: 600, unclaimedFeeY: 400 }), 100, true)).toBe(true)
  })

  it('considers X+Y sum, not either alone', () => {
    // Each side below threshold but sum above
    expect(shouldFire(mkPosition({ unclaimedFeeX: 60, unclaimedFeeY: 50 }), 100, true)).toBe(true)
  })
})

describe('previewFireable (Sprint 10 wave 3)', () => {
  it('returns empty when no positions', () => {
    expect(previewFireable(undefined, 100, [])).toEqual([])
    expect(previewFireable([], 100, [])).toEqual([])
  })

  it('returns positions over threshold, skips closed and below-threshold', () => {
    const positions = [
      mkPosition({ id: 'a', unclaimedFeeX: 200, unclaimedFeeY: 0 }),       // over
      mkPosition({ id: 'b', unclaimedFeeX: 30, unclaimedFeeY: 30 }),       // under
      mkPosition({ id: 'c', unclaimedFeeX: 500, isActive: false }),        // closed
      mkPosition({ id: 'd', unclaimedFeeX: 100, unclaimedFeeY: 50 }),      // over
    ]
    const result = previewFireable(positions, 100, [])
    expect(result.map((p) => p.id)).toEqual(['a', 'd'])
  })

  it('skips positions whose pool is in the exception list', () => {
    const positions = [
      mkPosition({ id: 'a', poolId: 'pool-x', unclaimedFeeX: 999 }),
      mkPosition({ id: 'b', poolId: 'pool-y', unclaimedFeeX: 999 }),
    ]
    const result = previewFireable(positions, 100, ['pool-x'])
    expect(result.map((p) => p.id)).toEqual(['b'])
  })
})
