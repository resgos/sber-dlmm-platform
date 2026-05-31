import apiClient from './client'
import type { Transaction, PageResponse, TransactionFilters } from './types'
import { scaleTransaction } from './scale'

export const transactions = {
  getMyTransactions: async (
    page = 0,
    size = 20,
    filters?: TransactionFilters,
  ): Promise<PageResponse<Transaction>> => {
    const { data } = await apiClient.get<PageResponse<Transaction>>('/transactions/me', {
      params: { page, size, ...filters },
    })
    return { ...data, content: data.content.map(scaleTransaction) }
  },

  getTransaction: async (id: string): Promise<Transaction> => {
    const { data } = await apiClient.get<Transaction>(`/transactions/${id}`)
    return scaleTransaction(data)
  },

  /**
   * Sprint 9-DS-r4 (P1-6) — Meteora-style pool-scoped recent feed.
   * Backs the "История" panel below the bin chart on PoolDetailPage.
   * Backend clamps to [1,100]; client sends 20 by default.
   */
  getRecentPoolTransactions: async (
    poolId: string,
    limit = 20,
  ): Promise<Transaction[]> => {
    const { data } = await apiClient.get<Transaction[]>(
      `/transactions/pool/${poolId}`,
      { params: { limit } },
    )
    return data.map(scaleTransaction)
  },
}
