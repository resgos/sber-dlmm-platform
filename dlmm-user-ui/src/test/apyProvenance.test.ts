import { describe, it, expect } from 'vitest'
import { apyProvenance, APY_TURNOVER_FLOOR } from '@/lib/apyProvenance'

describe('apyProvenance', () => {
  it('returns NONE for an empty pool (no liquidity)', () => {
    expect(apyProvenance({ volume24h: 0, totalTvlX: 0, totalTvlY: 0 })).toBe('NONE')
    expect(apyProvenance({ volume24h: 5_000, totalTvlX: 0, totalTvlY: 0 })).toBe('NONE')
  })

  it('returns MODEL when 24h turnover is below the floor', () => {
    // SBER-like shape: huge TVL, thin volume → backend floors APY to the model.
    expect(apyProvenance({ volume24h: 11_998_839_261, totalTvlX: 459_000_000_000, totalTvlY: 138_647_679_700_000 }))
      .toBe('MODEL')
    // Zero recent volume is the canonical MODEL case.
    expect(apyProvenance({ volume24h: 0, totalTvlX: 500_000, totalTvlY: 500_000 })).toBe('MODEL')
  })

  it('returns LIVE when 24h turnover meets or beats the floor', () => {
    // 100% daily turnover → unambiguously real-volume-driven.
    expect(apyProvenance({ volume24h: 1_000_000, totalTvlX: 500_000, totalTvlY: 500_000 })).toBe('LIVE')
  })

  it('uses the turnover floor as the LIVE/MODEL boundary, fee-independent', () => {
    const tvl = 1_000_000
    // Exactly at the floor → LIVE (>=).
    expect(apyProvenance({ volume24h: Math.ceil(tvl * APY_TURNOVER_FLOOR), totalTvlX: tvl, totalTvlY: 0 }))
      .toBe('LIVE')
    // A hair below the floor → MODEL.
    expect(apyProvenance({ volume24h: Math.floor(tvl * APY_TURNOVER_FLOOR) - 1, totalTvlX: tvl, totalTvlY: 0 }))
      .toBe('MODEL')
  })

  it('is scale-invariant (raw ×10^4 vs human give the same verdict)', () => {
    const raw = apyProvenance({ volume24h: 40_000_000, totalTvlX: 1_000_000_000, totalTvlY: 0 })
    const human = apyProvenance({ volume24h: 4_000, totalTvlX: 100_000, totalTvlY: 0 })
    expect(raw).toBe(human)
  })
})
