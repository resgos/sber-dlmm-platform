import apiClient from './client'
import type { DashboardData, PoolAnalytics, TokenAnalytics } from './types'
import { pools } from './pools'
import { scaleDashboard, scaleTokenAnalytics } from './scale'

export const admin = {
  getDashboard: async (): Promise<DashboardData> => {
    const response = await apiClient.get<DashboardData>('/admin/dashboard')
    return scaleDashboard(response.data)
  },
  getPoolAnalytics: async (id: string): Promise<PoolAnalytics> => {
    // delegates to pools.getPoolAnalytics, which already scales.
    return pools.getPoolAnalytics(id)
  },
  getTokenAnalytics: async (id: string): Promise<TokenAnalytics> => {
    const response = await apiClient.get<TokenAnalytics>(`/admin/tokens/${id}/analytics`)
    return scaleTokenAnalytics(response.data)
  },
}
