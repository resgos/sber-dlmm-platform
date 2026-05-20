import apiClient from './client'
import type {
  Pool,
  PoolDetail,
  PageResponse,
  SwapRequest,
  SwapQuote,
  AddLiquidityRequest,
  RemoveLiquidityRequest,
  Position,
} from './types'

export const pools = {
  getPools: async (page = 0, size = 20): Promise<PageResponse<Pool>> => {
    const { data } = await apiClient.get<PageResponse<Pool>>('/pools', {
      params: { page, size },
    })
    return data
  },

  getPool: async (id: string): Promise<PoolDetail> => {
    const { data } = await apiClient.get(`/pools/${id}`)
    // Backend returns {pool: {...}, bins: [...], volatilityAccumulator, ...}
    if (data.pool) {
      return {
        ...data.pool,
        bins: data.bins || [],
        volatilityAccumulator: data.volatilityAccumulator ?? 0,
        currentDynamicFeeBps: data.currentDynamicFeeBps ?? 0,
        totalFeesCollectedX: data.totalFeesCollectedX ?? 0,
        totalFeesCollectedY: data.totalFeesCollectedY ?? 0,
      } as PoolDetail
    }
    return data as PoolDetail
  },

  getSwapQuote: async (req: Omit<SwapRequest, 'idempotencyKey' | 'minAmountOut'>): Promise<SwapQuote> => {
    // Sprint 9-DS-r2 — backend record uses `estimated*` field names,
    // map to the friendlier names the UI was already assuming. The
    // mismatch silently produced undefined → NaN → toFixed crash on
    // the Swap page.
    interface RawQuote {
      poolId: string
      tokenInId: string
      tokenOutId: string
      amountIn: number
      estimatedAmountOut: number
      estimatedFee: number
      estimatedFeeBps: number
      estimatedBinsCrossed: number
      estimatedPrice: number
      priceImpactPct: number
    }
    const { data } = await apiClient.post<RawQuote>('/pools/swap/quote', req)
    return {
      poolId: data.poolId,
      tokenInId: data.tokenInId,
      tokenOutId: data.tokenOutId,
      amountIn: data.amountIn,
      amountOut: data.estimatedAmountOut,
      fee: data.estimatedFee,
      feeBps: data.estimatedFeeBps,
      binsCrossed: data.estimatedBinsCrossed,
      estimatedPrice: data.estimatedPrice,
      priceImpact: data.priceImpactPct,
    }
  },

  executeSwap: async (req: SwapRequest): Promise<void> => {
    await apiClient.post('/pools/swap', req)
  },

  addLiquidity: async (req: AddLiquidityRequest): Promise<void> => {
    await apiClient.post('/pools/add-liquidity', req)
  },

  removeLiquidity: async (req: RemoveLiquidityRequest): Promise<void> => {
    // Sprint 9-DS-r2 — backend expects `percentageBps` (1-10000 = 0.01%-100%);
    // UI passes `percentage` (1-100). Multiply by 100 to convert. Without
    // this, every Remove Liquidity call failed validation with
    // "percentageBps must be between 1 and 10000".
    const body = {
      positionId: req.positionId,
      percentageBps: Math.round(req.percentage * 100),
      idempotencyKey: req.idempotencyKey,
    }
    await apiClient.post('/pools/remove-liquidity', body)
  },

  getMyPositions: async (): Promise<Position[]> => {
    const { data } = await apiClient.get<Position[]>('/pools/positions/me')
    return data
  },
}
