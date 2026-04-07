import apiClient from './client'
import type {
  Transaction,
  SuspiciousTransaction,
  PageResponse,
  TransactionFilters,
} from './types'

export const transactions = {
  getTransactions: async (
    page = 0,
    size = 20,
    filters?: TransactionFilters,
  ): Promise<PageResponse<Transaction>> => {
    const params: Record<string, unknown> = { page, size, ...filters }
    const response = await apiClient.get<PageResponse<Transaction>>('/admin/transactions', {
      params,
    })
    return response.data
  },
  getSuspiciousTransactions: async (): Promise<SuspiciousTransaction[]> => {
    const response = await apiClient.get<SuspiciousTransaction[]>(
      '/admin/transactions/suspicious',
    )
    return response.data
  },
}
