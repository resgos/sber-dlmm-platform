import apiClient from './client'
import type { FeeSummary, FeeHistoryEntry, PageResponse, ClaimFeesRequest } from './types'

// Sprint 12 G-16 — backend swap-in for auto-claim. Wire format
// mirrors `AutoClaimPolicy` in autoClaimStore.ts (kept here too so
// the store can import from the api package without a circular dep).
export interface AutoClaimPolicyWire {
  enabled: boolean
  /** server stores as NUMERIC(38,0); JS number ok up to 2^53. */
  thresholdAmount: number
  dailyCap: number
  skipPoolIds: string[]
}

export interface AutoClaimLogEntry {
  id: string
  positionId: string
  poolId: string
  amountX: number
  amountY: number
  status: 'SUCCESS' | 'FAILURE' | 'SKIPPED'
  errorMessage: string | null
  firedAt: string
}

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

  // Sprint 12 G-16 — auto-claim policy CRUD.
  getAutoClaimPolicy: async (): Promise<AutoClaimPolicyWire> => {
    const { data } = await apiClient.get<AutoClaimPolicyWire>('/fees/me/auto-claim-policy')
    return data
  },

  putAutoClaimPolicy: async (policy: AutoClaimPolicyWire): Promise<AutoClaimPolicyWire> => {
    const { data } = await apiClient.put<AutoClaimPolicyWire>('/fees/me/auto-claim-policy', policy)
    return data
  },

  resetAutoClaimPolicy: async (): Promise<AutoClaimPolicyWire> => {
    const { data } = await apiClient.delete<AutoClaimPolicyWire>('/fees/me/auto-claim-policy')
    return data
  },

  getAutoClaimHistory: async (limit = 20): Promise<AutoClaimLogEntry[]> => {
    const { data } = await apiClient.get<AutoClaimLogEntry[]>('/fees/me/auto-claim-policy/history', {
      params: { limit },
    })
    return data
  },
}
