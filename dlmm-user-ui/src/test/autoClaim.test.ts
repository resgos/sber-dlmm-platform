import { describe, it, expect, beforeEach, vi } from 'vitest'

// Sprint 12 G-16 — backend swap. The store now hydrates from the
// server on first read and pushes to the server on every write. Tests
// mock the api/services module so writes are observable + reads return
// our test fixtures.
//
// All assertions about LOCAL cache behaviour (read-or-default, cache
// invariant, etc.) still hold — the server hydration is best-effort
// and never blocks the sync API surface.

const apiMocks = vi.hoisted(() => ({
  getAutoClaimPolicy: vi.fn(async () => ({
    enabled: false,
    thresholdAmount: 1000,
    dailyCap: 20,
    skipPoolIds: [],
  })),
  putAutoClaimPolicy: vi.fn(async (p: any) => p),
  resetAutoClaimPolicy: vi.fn(async () => ({
    enabled: false,
    thresholdAmount: 1000,
    dailyCap: 20,
    skipPoolIds: [],
  })),
  getAutoClaimHistory: vi.fn(async () => []),
}))

vi.mock('@/api/services', () => ({
  fees: apiMocks,
}))

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

describe('autoClaimStore (Sprint 12 G-16 backend swap)', () => {
  beforeEach(() => {
    localStorage.clear()
    autoClaimStore.__resetSideStateForTests()
    // Reset mocks between tests so call counts don't leak.
    apiMocks.getAutoClaimPolicy.mockClear()
    apiMocks.putAutoClaimPolicy.mockClear()
    apiMocks.resetAutoClaimPolicy.mockClear()
    apiMocks.getAutoClaimHistory.mockClear()
  })

  it('returns disabled default when nothing is persisted', () => {
    const p = autoClaimStore.get()
    expect(p.enabled).toBe(false)
    expect(p.threshold).toBeGreaterThan(0)
  })

  it('set persists + notifies subscribers + fires PUT', () => {
    let notified = 0
    const unsub = autoClaimStore.subscribe(() => { notified++ })
    autoClaimStore.set({ enabled: true, threshold: 500, dailyCap: 20, skipPoolIds: [] })
    expect(notified).toBeGreaterThan(0)
    expect(autoClaimStore.get()).toMatchObject({ enabled: true, threshold: 500, dailyCap: 20, skipPoolIds: [] })
    // Backend PUT issued.
    expect(apiMocks.putAutoClaimPolicy).toHaveBeenCalledWith({
      enabled: true,
      thresholdAmount: 500,
      dailyCap: 20,
      skipPoolIds: [],
    })
    unsub()
  })

  it('Sprint 10 wave 3: dailyCap honoured — isCappedToday flips at the limit', () => {
    autoClaimStore.set({ enabled: true, threshold: 100, dailyCap: 2, skipPoolIds: [] })
    expect(autoClaimStore.isCappedToday()).toBe(false)
    autoClaimStore.recordFired('pos-a', 'X/Y', 100)
    expect(autoClaimStore.isCappedToday()).toBe(false)
    autoClaimStore.recordFired('pos-b', 'X/Y', 200)
    expect(autoClaimStore.isCappedToday()).toBe(true)
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
    localStorage.setItem('dlmm.user.autoClaim', JSON.stringify({ enabled: true, threshold: 500 }))
    const p = autoClaimStore.get()
    expect(p.dailyCap).toBe(20)
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
    for (let i = 0; i < 25; i++) {
      autoClaimStore.recordFired(`pos-${i}`, 'X/Y', 100)
    }
    expect(autoClaimStore.history().length).toBeLessThanOrEqual(20)
  })

  it('Sprint 12 G-16: get() kicks off a server hydration on first call', async () => {
    autoClaimStore.get()
    // Allow the .then microtask to flush.
    await new Promise((r) => setTimeout(r, 0))
    expect(apiMocks.getAutoClaimPolicy).toHaveBeenCalledTimes(1)
  })

  it('Sprint 12 G-16: server hydration updates the cache with server values', async () => {
    apiMocks.getAutoClaimPolicy.mockResolvedValueOnce({
      enabled: true,
      thresholdAmount: 9999,
      dailyCap: 5,
      skipPoolIds: ['pool-srv'],
    })
    autoClaimStore.get() // triggers hydration
    await new Promise((r) => setTimeout(r, 0))
    const after = autoClaimStore.get()
    expect(after.enabled).toBe(true)
    expect(after.threshold).toBe(9999)
    expect(after.dailyCap).toBe(5)
    expect(after.skipPoolIds).toEqual(['pool-srv'])
  })

  it('Sprint 12 G-16: failed PUT reverts the cache to the prior snapshot', async () => {
    autoClaimStore.set({ enabled: false, threshold: 100, dailyCap: 5, skipPoolIds: [] })
    apiMocks.putAutoClaimPolicy.mockRejectedValueOnce(new Error('boom'))
    autoClaimStore.set({ enabled: true, threshold: 999, dailyCap: 10, skipPoolIds: [] })
    // Immediately after the sync set, the optimistic value is visible.
    expect(autoClaimStore.get().enabled).toBe(true)
    // After the PUT promise rejects, we revert.
    await new Promise((r) => setTimeout(r, 0))
    const reverted = autoClaimStore.get()
    expect(reverted.enabled).toBe(false)
    expect(reverted.threshold).toBe(100)
  })

  it('Sprint 12 G-16: fetchServerHistory delegates to the API', async () => {
    apiMocks.getAutoClaimHistory.mockResolvedValueOnce([
      {
        id: 'log-1',
        positionId: 'pos-h',
        poolId: 'pool-h',
        amountX: 100,
        amountY: 200,
        status: 'SUCCESS',
        errorMessage: null,
        firedAt: '2026-05-25T10:00:00',
      },
    ])
    const history = await autoClaimStore.fetchServerHistory(5)
    expect(apiMocks.getAutoClaimHistory).toHaveBeenCalledWith(5)
    expect(history).toHaveLength(1)
    expect(history[0].status).toBe('SUCCESS')
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
      mkPosition({ id: 'a', unclaimedFeeX: 200, unclaimedFeeY: 0 }),
      mkPosition({ id: 'b', unclaimedFeeX: 30, unclaimedFeeY: 30 }),
      mkPosition({ id: 'c', unclaimedFeeX: 500, isActive: false }),
      mkPosition({ id: 'd', unclaimedFeeX: 100, unclaimedFeeY: 50 }),
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
