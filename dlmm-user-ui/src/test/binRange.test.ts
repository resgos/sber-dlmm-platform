import { describe, it, expect } from 'vitest'
import { presetBinRange, BIN_RANGE_PRESETS } from '@/lib/binRange'

describe('presetBinRange (add-liquidity range presets)', () => {
  it('centres a symmetric ±halfWidth window on the active bin', () => {
    // real seed centre 8388608, ±5 → the [8388603, 8388613] range the demo uses
    expect(presetBinRange(8388608, 5)).toEqual({ min: 8388603, max: 8388613 })
    expect(presetBinRange(8388608, 15)).toEqual({ min: 8388593, max: 8388623 })
  })

  it('clamps min to 0 (bin ids are non-negative)', () => {
    expect(presetBinRange(3, 5)).toEqual({ min: 0, max: 8 })
    expect(presetBinRange(0, 40)).toEqual({ min: 0, max: 40 })
  })

  it('floors fractional inputs to whole bins', () => {
    expect(presetBinRange(100.9, 5.7)).toEqual({ min: 95, max: 105 })
  })

  it('exposes narrow→wide presets in ascending order', () => {
    const widths = BIN_RANGE_PRESETS.map((p) => p.halfWidth)
    expect(widths).toEqual([...widths].sort((a, b) => a - b))
    expect(widths.length).toBeGreaterThanOrEqual(3)
  })
})
