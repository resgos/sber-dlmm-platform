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

  /**
   * Sprint 9-DS-r4 (P2-12) — admin "Mark reviewed" action. POSTs to
   * admin-bff which forwards to transaction-service to stamp
   * reviewedAt/reviewedBy on the transaction row. The next refresh
   * of getSuspiciousTransactions then omits the row.
   */
  markReviewed: async (txId: string): Promise<void> => {
    await apiClient.post(`/admin/transactions/${txId}/review`)
  },
}
