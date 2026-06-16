import { describe, it, expect } from 'vitest'
import { isPositionInRange } from '@/lib/positionHealth'
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
