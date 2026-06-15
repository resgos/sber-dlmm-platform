import apiClient from './client'
import type {
  Transaction,
  SuspiciousTransaction,
  PageResponse,
  TransactionFilters,
} from './types'
import { scaleTransaction, scaleSuspiciousTransaction } from './scale'

export const transactions = {
  getTransactions: async (
    page = 0,
    size = 20,
    filters?: TransactionFilters,
  ): Promise<PageResponse<Transaction>> => {
    // admin-bff reads txType / status / from / to. The UI carries dates as
    // dateFrom / dateTo, so map them (widening to full-day LocalDateTime bounds)
    // — spreading them raw left the date filter a silent no-op. axios drops
    // undefined params.
    const params: Record<string, unknown> = {
      page,
      size,
      txType: filters?.txType,
      status: filters?.status,
      from: filters?.dateFrom ? `${filters.dateFrom}T00:00:00` : undefined,
      to: filters?.dateTo ? `${filters.dateTo}T23:59:59` : undefined,
    }
    const response = await apiClient.get<PageResponse<Transaction>>('/admin/transactions', {
      params,
    })
    return { ...response.data, content: response.data.content.map(scaleTransaction) }
  },
  getSuspiciousTransactions: async (): Promise<SuspiciousTransaction[]> => {
    const response = await apiClient.get<SuspiciousTransaction[]>(
      '/admin/transactions/suspicious',
    )
    return response.data.map(scaleSuspiciousTransaction)
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
