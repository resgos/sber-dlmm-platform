import { describe, it, expect } from 'vitest'
import { buildPortfolioSegments } from '../lib/portfolioStructure'

const bal = (symbol: string, available: number, locked = 0) => ({ symbol, available, locked })

describe('buildPortfolioSegments (dashboard «Структура портфеля»)', () => {
  it('splits into top-N tokens + others + deployed + fees; pcts sum to ~100', () => {
    const segs = buildPortfolioSegments(
      [bal('SRUB', 1000), bal('SBTC', 2, 0), bal('SUSDT', 100), bal('SETH', 10), bal('SBER', 5)],
      new Map([['SRUB', 1], ['SBTC', 100], ['SUSDT', 1], ['SETH', 10], ['SBER', 2]]),
      500, // deployed
      50, // fees
      3,
    )
    // wallet: SRUB 1000, SBTC 200, SUSDT 100, SETH 100, SBER 10 → top3 = SRUB, SBTC, SUSDT(or SETH tie)
    expect(segs.filter((s) => s.kind === 'token')).toHaveLength(3)
    expect(segs.find((s) => s.kind === 'others')).toBeTruthy()
    expect(segs.find((s) => s.kind === 'deployed')?.value).toBe(500)
    expect(segs.find((s) => s.kind === 'fees')?.value).toBe(50)
    const pctSum = segs.reduce((s, x) => s + x.pct, 0)
    expect(pctSum).toBeGreaterThan(99.5)
    expect(pctSum).toBeLessThan(100.5)
    // largest wallet token first
    expect(segs[0]).toMatchObject({ kind: 'token', symbol: 'SRUB' })
  })

  it('empty book → []', () => {
    expect(buildPortfolioSegments([], new Map(), 0, 0)).toEqual([])
  })

  it('unpriced tokens contribute nothing (no NaN)', () => {
    const segs = buildPortfolioSegments([bal('XXX', 100)], new Map(), 200, 0)
    expect(segs).toHaveLength(1)
    expect(segs[0]).toMatchObject({ kind: 'deployed', value: 200, pct: 100 })
  })

  it('omits zero segments (no others/deployed/fees rows when 0)', () => {
    const segs = buildPortfolioSegments([bal('SRUB', 100)], new Map([['SRUB', 1]]), 0, 0)
    expect(segs).toHaveLength(1)
    expect(segs[0].kind).toBe('token')
  })

  it('demo-like skew: dominant wallet + tiny LP still yields a visible deployed segment', () => {
    // ~6.33T wallet vs 411.5M LP (real demo ratio) → pct rounds to 0.01
    const segs = buildPortfolioSegments(
      [bal('SRUB', 6_332_412_793_541)],
      new Map([['SRUB', 1]]),
      411_550_037,
      0,
    )
    const dep = segs.find((s) => s.kind === 'deployed')
    expect(dep).toBeTruthy()
    expect(dep!.pct).toBeGreaterThan(0)
    expect(dep!.pct).toBeLessThan(0.02)
  })
})
