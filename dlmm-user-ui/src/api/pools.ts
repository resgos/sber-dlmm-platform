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
    const { data } = await apiClient.get<PoolDetail>(`/pools/${id}`)
    return data
  },

  getSwapQuote: async (req: Omit<SwapRequest, 'idempotencyKey' | 'minAmountOut'>): Promise<SwapQuote> => {
    const { data } = await apiClient.post<SwapQuote>('/pools/swap/quote', req)
    return data
  },

  executeSwap: async (req: SwapRequest): Promise<void> => {
    await apiClient.post('/pools/swap', req)
  },

  addLiquidity: async (req: AddLiquidityRequest): Promise<void> => {
    await apiClient.post('/pools/add-liquidity', req)
  },

  removeLiquidity: async (req: RemoveLiquidityRequest): Promise<void> => {
    await apiClient.post('/pools/remove-liquidity', req)
  },

  getMyPositions: async (): Promise<Position[]> => {
    const { data } = await apiClient.get<Position[]>('/pools/positions/me')
    return data
  },
}
