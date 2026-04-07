import apiClient from './client'
import type { DashboardData, PoolAnalytics, TokenAnalytics } from './types'
import { pools } from './pools'

export const admin = {
  getDashboard: async (): Promise<DashboardData> => {
    const response = await apiClient.get<DashboardData>('/admin/dashboard')
    return response.data
  },
  getPoolAnalytics: async (id: string): Promise<PoolAnalytics> => {
    return pools.getPoolAnalytics(id)
  },
  getTokenAnalytics: async (id: string): Promise<TokenAnalytics> => {
    const response = await apiClient.get<TokenAnalytics>(`/admin/tokens/${id}/analytics`)
    return response.data
  },
}
