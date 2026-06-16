import { describe, it, expect } from 'vitest'
import { isPositionInRange, summarizePositionRanges } from '@/lib/positionHealth'
import type { Position, Pool } from '@/api/types'

const pos = (min: number, max: number) => ({ binRangeMin: min, binRangeMax: max } as Position)
const pool = (activeBinId: number) => ({ activeBinId } as Pool)

describe('isPositionInRange (out-of-range earning badge)', () => {
  it('returns null when the pool is not loaded (can’t tell)', () => {
    expect(isPositionInRange(pos(100, 200), undefined)).toBeNull()
  })

  it('is true when the active bin sits inside the range (earning)', () => {
    expect(isPositionInRange(pos(100, 200), pool(150))).toBe(true)
  })

  it('is true at the inclusive boundaries', () => {
    expect(isPositionInRange(pos(100, 200), pool(100))).toBe(true)
    expect(isPositionInRange(pos(100, 200), pool(200))).toBe(true)
  })

  it('is false when the active bin is below or above the range (not earning)', () => {
    expect(isPositionInRange(pos(100, 200), pool(99))).toBe(false)
    expect(isPositionInRange(pos(100, 200), pool(201))).toBe(false)
  })
})

describe('summarizePositionRanges (portfolio KPI roll-up)', () => {
  const p = (poolId: string, min: number, max: number) =>
    ({ poolId, binRangeMin: min, binRangeMax: max } as Position)

  it('returns all-zero for an empty portfolio', () => {
    expect(summarizePositionRanges([], new Map())).toEqual({ inRange: 0, outOfRange: 0, unknown: 0 })
  })

  it('buckets each position into in-range / out-of-range / unknown', () => {
    const poolById = new Map<string, Pool>([
      ['A', { activeBinId: 150 } as Pool], // inside [100,200] → in range
      ['B', { activeBinId: 50 } as Pool],  // below [100,200]  → out of range
    ])
    const positions = [
      p('A', 100, 200), // in
      p('A', 100, 200), // in
      p('B', 100, 200), // out
      p('C', 100, 200), // pool C not loaded → unknown (must NOT count as out)
    ]
    expect(summarizePositionRanges(positions, poolById)).toEqual({ inRange: 2, outOfRange: 1, unknown: 1 })
  })
})
