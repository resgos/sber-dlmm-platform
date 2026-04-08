import apiClient from './client'
import type { Transaction, PageResponse, TransactionFilters } from './types'

export const transactions = {
  getMyTransactions: async (
    page = 0,
    size = 20,
    filters?: TransactionFilters,
  ): Promise<PageResponse<Transaction>> => {
    const { data } = await apiClient.get<PageResponse<Transaction>>('/transactions/me', {
      params: { page, size, ...filters },
    })
    return data
  },

  getTransaction: async (id: string): Promise<Transaction> => {
    const { data } = await apiClient.get<Transaction>(`/transactions/${id}`)
    return data
  },
}
