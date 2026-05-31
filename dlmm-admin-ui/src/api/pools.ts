import apiClient from './client'
import type {
  Pool,
  PoolDetail,
  PoolAnalytics,
  PageResponse,
  CreatePoolRequest,
} from './types'
import { scalePool, scalePoolDetail, scalePoolAnalytics } from './scale'

export const pools = {
  getPools: async (page = 0, size = 20): Promise<PageResponse<Pool>> => {
    const response = await apiClient.get<PageResponse<Pool>>('/admin/pools', {
      params: { page, size },
    })
    return { ...response.data, content: response.data.content.map(scalePool) }
  },
  getPool: async (id: string): Promise<PoolDetail> => {
    const response = await apiClient.get<PoolDetail>(`/admin/pools/${id}`)
    return scalePoolDetail(response.data)
  },
  createPool: async (data: CreatePoolRequest): Promise<Pool> => {
    // initialPrice is a price (ratio) — not scaled; the rest are config.
    const response = await apiClient.post<Pool>('/admin/pools', data)
    return scalePool(response.data)
  },
  pausePool: async (id: string): Promise<Pool> => {
    const response = await apiClient.post<Pool>(`/admin/pools/${id}/pause`)
    return scalePool(response.data)
  },
  resumePool: async (id: string): Promise<Pool> => {
    const response = await apiClient.post<Pool>(`/admin/pools/${id}/resume`)
    return scalePool(response.data)
  },
  emergencyShutdown: async (id: string): Promise<Pool> => {
    const response = await apiClient.post<Pool>(`/admin/pools/${id}/emergency-shutdown`)
    return scalePool(response.data)
  },
  getPoolAnalytics: async (id: string): Promise<PoolAnalytics> => {
    const response = await apiClient.get<PoolAnalytics>(`/admin/pools/${id}/analytics`)
    return scalePoolAnalytics(response.data)
  },
}
