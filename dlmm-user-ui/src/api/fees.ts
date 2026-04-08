import apiClient from './client'
import type { FeeSummary, FeeHistoryEntry, PageResponse, ClaimFeesRequest } from './types'

export const fees = {
  getMyFeeSummary: async (): Promise<FeeSummary> => {
    const { data } = await apiClient.get<FeeSummary>('/fees/me/summary')
    return data
  },

  getMyFeeHistory: async (page = 0, size = 20): Promise<PageResponse<FeeHistoryEntry>> => {
    const { data } = await apiClient.get<PageResponse<FeeHistoryEntry>>('/fees/me/history', {
      params: { page, size },
    })
    return data
  },

  claimFees: async (req: ClaimFeesRequest): Promise<void> => {
    await apiClient.post('/fees/claim', req)
  },
}
