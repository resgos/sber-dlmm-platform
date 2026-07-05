import apiClient from './client'
import type {
  Transaction,
  SuspiciousTransaction,
  PageResponse,
  TransactionFilters,
} from './types'
import { scaleTransaction, scaleSuspiciousTransaction } from './scale'
import { utcStartOfLocalDay, utcEndOfLocalDay } from '@/lib/apiDates'

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
      // 2026-07-06 — same contract as user-ui (fcaa07f): the picked day is a
      // LOCAL calendar day, the ledger is UTC. Verbatim bounds shifted the
      // admin's «за сегодня» window by the viewer's offset — an operator
      // checking today's activity missed the late-UTC evening.
      from: filters?.dateFrom ? utcStartOfLocalDay(filters.dateFrom) : undefined,
      to: filters?.dateTo ? utcEndOfLocalDay(filters.dateTo) : undefined,
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
