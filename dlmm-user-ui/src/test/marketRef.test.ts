import { describe, it, expect } from 'vitest'
import { marketReference } from '@/lib/marketRef'

describe('marketReference (pool spot vs oracle)', () => {
  it('returns null when either price is missing or non-positive', () => {
    expect(marketReference(undefined, 100)).toBeNull()
    expect(marketReference(100, undefined)).toBeNull()
    expect(marketReference(0, 100)).toBeNull()
    expect(marketReference(100, 0)).toBeNull()
    expect(marketReference(100, -5)).toBeNull()
  })

  it('bands |Δ|<1% as fair (≈ market)', () => {
    expect(marketReference(100, 100)).toMatchObject({ deviationPct: 0, band: 'fair' })
    expect(marketReference(100.5, 100)?.band).toBe('fair')
    // real seed example: SBER pool 221.626 vs oracle 221.599 → ~0.012%
    expect(marketReference(221.626, 221.599)?.band).toBe('fair')
  })

  it('bands 1–3% as slight', () => {
    const r = marketReference(102, 100)
    expect(r?.deviationPct).toBeCloseTo(2, 5)
    expect(r?.band).toBe('slight')
    // VTBR seed example: 0.01865 vs 0.01822 → ~2.36%
    expect(marketReference(0.01865, 0.01822)?.band).toBe('slight')
  })

  it('bands >=3% as wide and keeps the sign', () => {
    expect(marketReference(105, 100)).toMatchObject({ band: 'wide' })
    const below = marketReference(90, 100)
    expect(below?.deviationPct).toBeCloseTo(-10, 5)
    expect(below?.band).toBe('wide')
  })
})
