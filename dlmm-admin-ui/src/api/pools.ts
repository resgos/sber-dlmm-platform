import apiClient from './client'
import type {
  Pool,
  PoolDetail,
  PoolAnalytics,
  PageResponse,
  CreatePoolRequest,
} from './types'

export const pools = {
  getPools: async (page = 0, size = 20): Promise<PageResponse<Pool>> => {
    const response = await apiClient.get<PageResponse<Pool>>('/admin/pools', {
      params: { page, size },
    })
    return response.data
  },
  getPool: async (id: string): Promise<PoolDetail> => {
    const response = await apiClient.get<PoolDetail>(`/admin/pools/${id}`)
    return response.data
  },
  createPool: async (data: CreatePoolRequest): Promise<Pool> => {
    const response = await apiClient.post<Pool>('/admin/pools', data)
    return response.data
  },
  pausePool: async (id: string): Promise<Pool> => {
    const response = await apiClient.post<Pool>(`/admin/pools/${id}/pause`)
    return response.data
  },
  resumePool: async (id: string): Promise<Pool> => {
    const response = await apiClient.post<Pool>(`/admin/pools/${id}/resume`)
    return response.data
  },
  emergencyShutdown: async (id: string): Promise<Pool> => {
    const response = await apiClient.post<Pool>(`/admin/pools/${id}/emergency-shutdown`)
    return response.data
  },
  getPoolAnalytics: async (id: string): Promise<PoolAnalytics> => {
    const response = await apiClient.get<PoolAnalytics>(`/admin/pools/${id}/analytics`)
    return response.data
  },
}
