import { describe, it, expect } from 'vitest'
import {
  AMOUNT_SCALE,
  fromRaw,
  toRaw,
  fromRawN,
  scaleBalance,
  scaleQuote,
  scalePool,
  scaleBin,
  scaleTransaction,
} from '@/api/scale'

/**
 * Sprint 16 (#14) — guards the uniform platform amount scale. The whole point
 * is that token *quantities* round-trip raw↔human while prices / fee-bps /
 * ratios stay untouched (a uniform scale leaves Y/X ratios invariant). A
 * regression here silently mis-displays every balance by 10000x, so pin both
 * the maths and the field selection.
 */
describe('api/scale — uniform platform amount scale', () => {
  it('AMOUNT_SCALE is 10_000 (4 platform decimals)', () => {
    expect(AMOUNT_SCALE).toBe(10_000)
  })

  it('fromRaw/toRaw round-trip a fractional amount (0.5 token)', () => {
    expect(fromRaw(5000)).toBe(0.5)
    expect(toRaw(0.5)).toBe(5000)
    expect(fromRaw(toRaw(123.4567))).toBeCloseTo(123.4567, 4)
  })

  it('toRaw rounds to the nearest raw unit and never emits a fraction', () => {
    expect(toRaw(0.00005)).toBe(1) // 0.5 raw → rounds up
    expect(toRaw(0.00004)).toBe(0)
    expect(Number.isInteger(toRaw(0.1 + 0.2))).toBe(true)
  })

  it('fromRawN preserves null/undefined for optional amount fields', () => {
    expect(fromRawN(null)).toBeNull()
    expect(fromRawN(undefined)).toBeUndefined()
    expect(fromRawN(20000)).toBe(2)
  })

  it('scaleBalance divides available/locked/total, keeps ids', () => {
    const b = scaleBalance({
      userId: 'u',
      tokenId: 't',
      symbol: 'SBTC',
      available: 11_210_000,
      locked: 0,
      total: 11_210_000,
    })
    expect(b.available).toBe(1121)
    expect(b.total).toBe(1121)
    expect(b.symbol).toBe('SBTC')
  })

  it('scaleQuote scales amounts but leaves price / fee-bps / impact alone', () => {
    const q = scaleQuote({
      poolId: 'p',
      tokenInId: 'a',
      tokenOutId: 'b',
      amountIn: 25_000_000_000,
      amountOut: 4987,
      fee: 62_500_000,
      feeBps: 25,
      binsCrossed: 0,
      estimatedPrice: 5_000_000,
      priceImpact: 0.01,
    })
    expect(q.amountIn).toBe(2_500_000)
    expect(q.amountOut).toBe(0.4987) // fractional SBTC — the whole point
    expect(q.fee).toBe(6250)
    expect(q.feeBps).toBe(25) // ratio — unchanged
    expect(q.estimatedPrice).toBe(5_000_000) // ratio — unchanged
    expect(q.priceImpact).toBe(0.01) // ratio — unchanged
  })

  it('scalePool scales tvl/volume but not price/bps', () => {
    const p = scalePool({
      id: 'p',
      tokenXId: 'x',
      tokenYId: 'y',
      tokenXSymbol: 'SBTC',
      tokenYSymbol: 'SRUB',
      binStep: 10,
      baseFeeBps: 25,
      activeBinId: 8_388_608,
      currentPrice: 5_000_000,
      totalTvlX: 30_000_000,
      totalTvlY: 50_000_000,
      volume24h: 10_000,
      estimatedApy: 12.5,
      status: 'ACTIVE',
      createdAt: '',
    })
    expect(p.totalTvlX).toBe(3000)
    expect(p.volume24h).toBe(1)
    expect(p.currentPrice).toBe(5_000_000) // unchanged
    expect(p.baseFeeBps).toBe(25) // unchanged
  })

  it('scaleBin scales reserves/liquidity but not price/binId', () => {
    const b = scaleBin({
      binId: 8_388_608,
      price: 5_000_000,
      liquidity: 100_000,
      reserveX: 20_000,
      reserveY: 40_000,
      compositionFactor: 0.5,
    })
    expect(b.reserveX).toBe(2)
    expect(b.liquidity).toBe(10)
    expect(b.binId).toBe(8_388_608) // unchanged
    expect(b.price).toBe(5_000_000) // unchanged
  })

  it('scaleTransaction is null-safe on optional amounts, leaves feeRate', () => {
    const t = scaleTransaction({
      id: 't',
      txType: 'SWAP',
      status: 'CONFIRMED',
      userId: 'u',
      poolId: 'p',
      tokenInId: 'a',
      amountIn: 25_000_000_000,
      tokenOutId: 'b',
      amountOut: 4987,
      feeAmount: null,
      feeRate: 0.0025,
      binsCrossed: 0,
      idempotencyKey: null,
      metadata: null,
      errorMessage: null,
      createdAt: '',
      updatedAt: '',
      confirmedAt: null,
    })
    expect(t.amountIn).toBe(2_500_000)
    expect(t.amountOut).toBe(0.4987)
    expect(t.feeAmount).toBeNull()
    expect(t.feeRate).toBe(0.0025) // ratio — unchanged
  })
})
