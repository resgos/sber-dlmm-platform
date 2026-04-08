import { describe, it, expect } from 'vitest'
import type {
  TokenBalance,
  Pool,
  PoolDetail,
  SwapRequest,
  AddLiquidityRequest,
  Transaction,
  FeeSummary,
  Notification,
  PageResponse,
} from '../api/types'

/**
 * Type-level tests: verify that our type definitions match the expected
 * backend API contract. These tests ensure type shapes are correct at compile
 * time and validate mock data conforms to the types at runtime.
 */
describe('API Types: contract validation', () => {
  it('TokenBalance matches backend response shape', () => {
    const balance: TokenBalance = {
      userId: 'a0000000-0000-0000-0000-000000000002',
      tokenId: 'b0000000-0000-0000-0000-000000000001',
      symbol: 'SRUB',
      available: 50000000000,
      locked: 2000000000,
      total: 52000000000,
    }
    expect(balance.symbol).toBe('SRUB')
    expect(balance.available + balance.locked).toBe(balance.total)
  })

  it('Pool has all required fields', () => {
    const pool: Pool = {
      id: 'c0000000-0000-0000-0000-000000000001',
      tokenXId: 'b1',
      tokenYId: 'b2',
      tokenXSymbol: 'SBTC',
      tokenYSymbol: 'SRUB',
      binStep: 100,
      baseFeeBps: 25,
      activeBinId: 8388608,
      currentPrice: 5000000,
      totalTvlX: 300000000000,
      totalTvlY: 1500000000000,
      volume24h: 850000000000,
      estimatedApy: 15.5,
      status: 'ACTIVE',
      createdAt: '2026-04-08T00:00:00',
    }
    expect(pool.binStep).toBeGreaterThan(0)
    expect(pool.baseFeeBps).toBeGreaterThan(0)
    expect(['ACTIVE', 'PAUSED', 'SHUTDOWN', 'PENDING']).toContain(pool.status)
  })

  it('PoolDetail extends Pool with bins', () => {
    const detail: PoolDetail = {
      id: 'c1',
      tokenXId: 'b1',
      tokenYId: 'b2',
      tokenXSymbol: 'SBTC',
      tokenYSymbol: 'SRUB',
      binStep: 100,
      baseFeeBps: 25,
      activeBinId: 8388608,
      currentPrice: 5000000,
      totalTvlX: 100,
      totalTvlY: 200,
      volume24h: 50,
      estimatedApy: 10,
      status: 'ACTIVE',
      createdAt: '2026-01-01',
      bins: [
        { binId: 8388608, price: 5000000, liquidity: 1000, reserveX: 500, reserveY: 500, compositionFactor: 0.5 },
      ],
      volatilityAccumulator: 42,
      currentDynamicFeeBps: 5,
      totalFeesCollectedX: 1000,
      totalFeesCollectedY: 2000,
    }
    expect(detail.bins).toHaveLength(1)
    expect(detail.volatilityAccumulator).toBeGreaterThanOrEqual(0)
  })

  it('SwapRequest has required fields including idempotencyKey', () => {
    const req: SwapRequest = {
      poolId: 'c1',
      tokenInId: 'b1',
      amountIn: 1000000,
      minAmountOut: 990000,
      idempotencyKey: crypto.randomUUID(),
    }
    expect(req.idempotencyKey).toBeDefined()
    expect(req.minAmountOut).toBeLessThanOrEqual(req.amountIn)
  })

  it('AddLiquidityRequest validates strategy values', () => {
    const req: AddLiquidityRequest = {
      poolId: 'c1',
      amountX: 1000,
      amountY: 2000,
      binRangeMin: 8388600,
      binRangeMax: 8388616,
      strategy: 'CURVE',
      idempotencyKey: crypto.randomUUID(),
    }
    expect(['SPOT', 'CURVE', 'BID_ASK']).toContain(req.strategy)
    expect(req.binRangeMax).toBeGreaterThan(req.binRangeMin)
  })

  it('Transaction covers all tx types', () => {
    const txTypes = ['SWAP', 'ADD_LIQUIDITY', 'REMOVE_LIQUIDITY', 'MINT', 'BURN', 'TRANSFER', 'CLAIM_FEE']
    const tx: Transaction = {
      id: 't1',
      txType: 'SWAP',
      status: 'CONFIRMED',
      userId: 'u1',
      poolId: 'c1',
      tokenInId: 'b1',
      amountIn: 1000,
      tokenOutId: 'b2',
      amountOut: 950,
      feeAmount: 50,
      feeRate: 0.005,
      binsCrossed: 3,
      idempotencyKey: 'key-1',
      metadata: null,
      errorMessage: null,
      createdAt: '2026-04-08T00:00:00',
      updatedAt: '2026-04-08T00:00:01',
      confirmedAt: '2026-04-08T00:00:02',
    }
    expect(txTypes).toContain(tx.txType)
  })

  it('PageResponse wraps any content type', () => {
    const page: PageResponse<Pool> = {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    }
    expect(page.content).toEqual([])
    expect(page.page).toBe(0)
  })

  it('FeeSummary has all earning fields', () => {
    const summary: FeeSummary = {
      totalEarnedX: 1000,
      totalEarnedY: 2000,
      totalClaimed: 500,
      totalUnclaimed: 2500,
    }
    expect(summary.totalClaimed + summary.totalUnclaimed).toBeLessThanOrEqual(
      summary.totalEarnedX + summary.totalEarnedY,
    )
  })

  it('Notification has all required fields', () => {
    const notif: Notification = {
      id: 'n1',
      userId: 'u1',
      type: 'SWAP_COMPLETED',
      title: 'Своп выполнен',
      message: 'Обмен 1 SBTC → 5M SRUB',
      isRead: false,
      createdAt: '2026-04-08T00:00:00',
      readAt: null,
    }
    expect(notif.isRead).toBe(false)
    expect(notif.readAt).toBeNull()
  })
})
