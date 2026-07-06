import apiClient from './client'
import type { Transaction, PageResponse, TransactionFilters, PoolFeeStats } from './types'
import { utcStartOfLocalDay, utcEndOfLocalDay } from '@/lib/apiDates'
import { scaleTransaction } from './scale'

export const transactions = {
  getMyTransactions: async (
    page = 0,
    size = 20,
    filters?: TransactionFilters,
  ): Promise<PageResponse<Transaction>> => {
    // Map the UI filter shape to the backend's query-param names. Spreading
    // `...filters` raw was a silent no-op: the controller reads `type` / `from`
    // / `to`, but the UI sends `txType` / `dateFrom` / `dateTo`, so the type and
    // date filters never reached the server (every query returned the full set).
    // Dates widen to full-day bounds so an inclusive YYYY-MM-DD range matches the
    // LocalDateTime column. axios omits undefined params.
    const { data } = await apiClient.get<PageResponse<Transaction>>('/transactions/me', {
      params: {
        page,
        size,
        type: filters?.txType,
        status: filters?.status,
        // 2026-07-06 — the picked day is a LOCAL calendar day; the ledger is
        // UTC. Send the day bounds converted to UTC instants (zoneless format
        // the backend parses) — verbatim local bounds shifted the window by
        // the viewer's offset (Moscow lost 21:00–23:59 UTC of its evening).
        from: filters?.dateFrom ? utcStartOfLocalDay(filters.dateFrom) : undefined,
        to: filters?.dateTo ? utcEndOfLocalDay(filters.dateTo) : undefined,
        poolId: filters?.poolId,
      },
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

  /**
   * 2026-06-17 — effective-fee stats over the pool's last N swaps. Rates are
   * bps (never amount-scaled — pass through untouched); aggregates are null
   * for pools with no recorded swap fee rates.
   */
  getPoolFeeStats: async (poolId: string, limit = 50): Promise<PoolFeeStats> => {
    const { data } = await apiClient.get<PoolFeeStats>(
      `/transactions/pool/${poolId}/fee-stats`,
      { params: { limit } },
    )
    return data
  },

  /**
   * 2026-07-06 — batch form for the pools LIST page: one round-trip for all
   * visible cards (≤50 ids) instead of N parallel calls through the gateway
   * rate limit. Rates are bps — never amount-scaled.
   */
  getPoolsFeeStats: async (poolIds: string[], limit = 50): Promise<PoolFeeStats[]> => {
    const { data } = await apiClient.get<PoolFeeStats[]>(
      '/transactions/pools/fee-stats',
      { params: { poolIds: poolIds.join(','), limit } },
    )
    return data
  },
}
