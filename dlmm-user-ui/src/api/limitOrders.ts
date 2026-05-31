import apiClient from './client'
import type { CreateLimitOrderRequest, LimitOrder, LimitOrderStatus } from './types'
import { scaleLimitOrder, toRaw } from './scale'

/**
 * Sprint 16 (Meteora parity) — DLMM limit orders. Lives under /pools/limit-orders
 * (pool-engine). amountIn is scaled human→raw on the way out; limitPrice is a
 * Y/X ratio and is sent as-is. Responses are scaled raw→human by scaleLimitOrder.
 */
export const limitOrders = {
  create: async (req: CreateLimitOrderRequest): Promise<LimitOrder> => {
    const { data } = await apiClient.post<LimitOrder>('/pools/limit-orders', {
      ...req,
      amountIn: toRaw(req.amountIn),
      // limitPrice: ratio, NOT scaled.
    })
    return scaleLimitOrder(data)
  },

  /** Current user's orders; optionally narrow to one pool and/or a status. */
  getMine: async (opts: { poolId?: string; status?: LimitOrderStatus } = {}): Promise<LimitOrder[]> => {
    const { data } = await apiClient.get<LimitOrder[]>('/pools/limit-orders/me', {
      params: { poolId: opts.poolId, status: opts.status },
    })
    return data.map(scaleLimitOrder)
  },

  cancel: async (id: string): Promise<LimitOrder> => {
    const { data } = await apiClient.delete<LimitOrder>(`/pools/limit-orders/${id}`)
    return scaleLimitOrder(data)
  },
}
