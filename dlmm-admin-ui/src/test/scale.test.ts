import { describe, it, expect } from 'vitest'
import {
  AMOUNT_SCALE,
  fromRaw,
  toRaw,
  scaleDashboard,
  scalePool,
  scaleTransaction,
  scaleOtcBlockTrade,
} from '@/api/scale'

/**
 * Sprint 16 (#14) — admin-ui amount scale. The trap here is the dashboard:
 * it mixes amount fields (*Rub) with COUNT fields (totalUsers, activePools…).
 * Scaling a count by 1/10000 would wreck the headline stats, so pin that the
 * scaler touches only the amounts. Also pin that prices (quotedPriceMicro) and
 * ratios survive untouched.
 */
describe('admin api/scale — uniform platform amount scale', () => {
  it('AMOUNT_SCALE is 10_000', () => {
    expect(AMOUNT_SCALE).toBe(10_000)
    expect(fromRaw(5000)).toBe(0.5)
    expect(toRaw(0.5)).toBe(5000)
  })

  it('scaleDashboard scales *Rub amounts but NOT the count fields', () => {
    const d = scaleDashboard({
      totalUsers: 1234,
      verifiedUsers: 1000,
      totalPools: 42,
      activePools: 40,
      totalTvlRub: 24_200_000_000,
      volume24hRub: 1_846_000_000,
      totalFeesCollectedRub: 127_400_000,
      activePositions: 357,
      transactionsToday: 89,
    })
    // amounts ÷ 10^4
    expect(d.totalTvlRub).toBe(2_420_000)
    expect(d.volume24hRub).toBe(184_600)
    expect(d.totalFeesCollectedRub).toBe(12_740)
    // counts untouched
    expect(d.totalUsers).toBe(1234)
    expect(d.activePools).toBe(40)
    expect(d.activePositions).toBe(357)
    expect(d.transactionsToday).toBe(89)
  })

  it('scalePool scales tvl/volume, leaves price/bps', () => {
    const p = scalePool({
      id: 'p', tokenXId: 'x', tokenYId: 'y', tokenXSymbol: 'SBTC', tokenYSymbol: 'SRUB',
      binStep: 10, baseFeeBps: 25, activeBinId: 8_388_608, currentPrice: 5_000_000,
      totalTvlX: 30_000_000, totalTvlY: 50_000_000, volume24h: 10_000,
      estimatedApy: 12.5, status: 'ACTIVE', createdAt: '',
    })
    expect(p.totalTvlX).toBe(3000)
    expect(p.currentPrice).toBe(5_000_000)
    expect(p.baseFeeBps).toBe(25)
  })

  it('scaleTransaction is null-safe and leaves feeRate', () => {
    const t = scaleTransaction({
      id: 't', txType: 'SWAP', status: 'CONFIRMED', userId: 'u', poolId: 'p',
      tokenInId: 'a', amountIn: 25_000_000_000, tokenOutId: 'b', amountOut: 4987,
      feeAmount: null, feeRate: 0.0025, binsCrossed: 0, idempotencyKey: null,
      metadata: null, errorMessage: null, createdAt: '', updatedAt: '', confirmedAt: null,
    })
    expect(t.amountIn).toBe(2_500_000)
    expect(t.amountOut).toBe(0.4987)
    expect(t.feeAmount).toBeNull()
    expect(t.feeRate).toBe(0.0025)
  })

  it('scaleOtcBlockTrade scales amounts, leaves quotedPriceMicro', () => {
    const o = scaleOtcBlockTrade({
      id: 'o', initiatorUserId: 'i', counterpartyUserId: 'c', createdByAdminId: 'a',
      tokenInId: 'x', tokenOutId: 'y', amountIn: 50_000_000, amountOut: 9_950_000,
      quotedPriceMicro: 199_000, status: 'QUOTED', createdAt: '', updatedAt: '',
    })
    expect(o.amountIn).toBe(5000)
    expect(o.amountOut).toBe(995)
    expect(o.quotedPriceMicro).toBe(199_000) // price — unchanged
  })
})
