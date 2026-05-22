import { describe, it, expect, beforeEach } from 'vitest'
import { positionAlertsStore, isOnCooldown, COOLDOWN_MS } from '../store/positionAlertsStore'
import { evaluateRule } from '../lib/usePositionAlertWatcher'
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
    createdAt: '2026-05-20T10:00:00Z',
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

describe('positionAlertsStore', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('starts empty', () => {
    expect(positionAlertsStore.list()).toEqual([])
  })

  it('adds + lists alerts', () => {
    const a = positionAlertsStore.add({
      positionId: 'pos-1',
      type: 'OUT_OF_RANGE',
      threshold: 0,
      label: 'test',
    })
    expect(positionAlertsStore.list()).toHaveLength(1)
    expect(a.id).toBeDefined()
    expect(a.active).toBe(true)
  })

  it('toggle flips active flag', () => {
    const a = positionAlertsStore.add({ positionId: 'p', type: 'OUT_OF_RANGE', threshold: 0, label: 'x' })
    expect(a.active).toBe(true)
    positionAlertsStore.toggle(a.id)
    expect(positionAlertsStore.list()[0].active).toBe(false)
  })

  it('remove deletes by id', () => {
    const a = positionAlertsStore.add({ positionId: 'p', type: 'OUT_OF_RANGE', threshold: 0, label: 'x' })
    positionAlertsStore.remove(a.id)
    expect(positionAlertsStore.list()).toEqual([])
  })

  it('isOnCooldown false when never fired', () => {
    const a = positionAlertsStore.add({ positionId: 'p', type: 'OUT_OF_RANGE', threshold: 0, label: 'x' })
    expect(isOnCooldown(a)).toBe(false)
  })

  it('isOnCooldown true within COOLDOWN_MS of last fire', () => {
    const a = positionAlertsStore.add({ positionId: 'p', type: 'OUT_OF_RANGE', threshold: 0, label: 'x' })
    positionAlertsStore.recordFired(a.id)
    const updated = positionAlertsStore.list()[0]
    expect(isOnCooldown(updated)).toBe(true)
  })

  it('isOnCooldown false after COOLDOWN_MS', () => {
    const a = positionAlertsStore.add({ positionId: 'p', type: 'OUT_OF_RANGE', threshold: 0, label: 'x' })
    positionAlertsStore.recordFired(a.id)
    const updated = positionAlertsStore.list()[0]
    // Synthesise an old timestamp by directly setting localStorage.
    const old = { ...updated, lastFiredAt: new Date(Date.now() - COOLDOWN_MS - 1000).toISOString() }
    localStorage.setItem('dlmm.user.positionAlerts', JSON.stringify([old]))
    expect(isOnCooldown(old)).toBe(false)
  })
})

describe('evaluateRule', () => {
  const baseAlert = {
    id: 'a-1',
    positionId: 'pos-1',
    label: 'test',
    active: true,
    lastFiredAt: null,
    createdAt: '2026-05-22T10:00:00Z',
  }

  it('OUT_OF_RANGE — silent when activeBin inside [min,max]', () => {
    expect(evaluateRule(
      { ...baseAlert, type: 'OUT_OF_RANGE', threshold: 0 },
      mkPosition({ binRangeMin: 100, binRangeMax: 110 }),
      mkPool({ activeBinId: 105 }),
    )).toBeNull()
  })

  it('OUT_OF_RANGE — fires when activeBin below min', () => {
    const msg = evaluateRule(
      { ...baseAlert, type: 'OUT_OF_RANGE', threshold: 0 },
      mkPosition({ binRangeMin: 100, binRangeMax: 110 }),
      mkPool({ activeBinId: 95 }),
    )
    expect(msg).toMatch(/вышла из диапазона/)
  })

  it('OUT_OF_RANGE — fires when activeBin above max', () => {
    const msg = evaluateRule(
      { ...baseAlert, type: 'OUT_OF_RANGE', threshold: 0 },
      mkPosition({ binRangeMin: 100, binRangeMax: 110 }),
      mkPool({ activeBinId: 200 }),
    )
    expect(msg).toMatch(/вышла из диапазона/)
  })

  it('FEES_THRESHOLD — silent below threshold', () => {
    expect(evaluateRule(
      { ...baseAlert, type: 'FEES_THRESHOLD', threshold: 1000 },
      mkPosition({ unclaimedFeeX: 200, unclaimedFeeY: 300 }),
      mkPool(),
    )).toBeNull()
  })

  it('FEES_THRESHOLD — fires when sum exceeds threshold', () => {
    const msg = evaluateRule(
      { ...baseAlert, type: 'FEES_THRESHOLD', threshold: 400 },
      mkPosition({ unclaimedFeeX: 250, unclaimedFeeY: 250 }),
      mkPool(),
    )
    expect(msg).toMatch(/Незабранные комиссии/)
  })

  it('VALUE_DROP — fires when drop exceeds threshold %', () => {
    const msg = evaluateRule(
      { ...baseAlert, type: 'VALUE_DROP', threshold: 10 },
      // Initial 100, current 80 → 20% drop.
      mkPosition({ initialDepositX: 50, initialDepositY: 50, currentValueX: 40, currentValueY: 40 }),
      mkPool(),
    )
    expect(msg).toMatch(/упала на 20\.0%/)
  })

  it('VALUE_DROP — silent for tiny drift inside threshold', () => {
    expect(evaluateRule(
      { ...baseAlert, type: 'VALUE_DROP', threshold: 10 },
      mkPosition({ initialDepositX: 100, initialDepositY: 100, currentValueX: 99, currentValueY: 99 }),
      mkPool(),
    )).toBeNull()
  })

  it('inactive alert never fires', () => {
    expect(evaluateRule(
      { ...baseAlert, type: 'OUT_OF_RANGE', threshold: 0, active: false },
      mkPosition({ binRangeMin: 100, binRangeMax: 110 }),
      mkPool({ activeBinId: 5000 }),
    )).toBeNull()
  })

  it('inactive position never fires (closed position should be ignored)', () => {
    expect(evaluateRule(
      { ...baseAlert, type: 'OUT_OF_RANGE', threshold: 0 },
      mkPosition({ isActive: false, binRangeMin: 100, binRangeMax: 110 }),
      mkPool({ activeBinId: 5000 }),
    )).toBeNull()
  })
})
