import apiClient from './client'
import type { PageResponse } from './types'
import { scaleOtcBlockTrade, toRaw } from './scale'

/**
 * Sprint 9 #6.1 — OTC desk API client.
 *
 * Mirrors {@code OtcDeskController} in dlmm-transaction-service. Admin-only
 * endpoints (gateway routes /api/v1/otc/** → transaction-service). All
 * methods authenticated via Bearer JWT injected by the axios interceptor.
 */

export type OtcStatus = 'REQUESTED' | 'QUOTED' | 'ACCEPTED' | 'SETTLED'
  | 'REJECTED' | 'EXPIRED' | 'CANCELLED'

export interface OtcBlockTrade {
  id: string
  initiatorUserId: string
  counterpartyUserId: string
  createdByAdminId: string
  tokenInId: string
  tokenOutId: string
  amountIn: number
  amountOut?: number | null
  quotedPriceMicro?: number | null
  quotedAt?: string | null
  quoteExpiresAt?: string | null
  status: OtcStatus
  settlementTxId?: string | null
  settledAt?: string | null
  notes?: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateOtcRequest {
  initiatorUserId: string
  counterpartyUserId: string
  tokenInId: string
  tokenOutId: string
  amountIn: number
  notes?: string
}

export interface QuoteOtcRequest {
  amountOut: number
  quotedPriceMicro: number
  quoteExpiresAt: string // ISO-8601
}

export interface SettleOtcRequest {
  settlementTxId: string
}

export interface ReasonRequest {
  reason?: string
}

export const otc = {
  list: async (params?: {
    status?: OtcStatus
    counterpartyUserId?: string
    initiatorUserId?: string
    page?: number
    size?: number
  }): Promise<PageResponse<OtcBlockTrade>> => {
    const { data } = await apiClient.get<PageResponse<OtcBlockTrade>>('/otc', { params })
    return { ...data, content: data.content.map(scaleOtcBlockTrade) }
  },

  getOne: async (id: string): Promise<OtcBlockTrade> => {
    const { data } = await apiClient.get<OtcBlockTrade>(`/otc/${id}`)
    return scaleOtcBlockTrade(data)
  },

  create: async (req: CreateOtcRequest): Promise<OtcBlockTrade> => {
    // amountIn is a human quantity — scale up to raw before sending.
    const { data } = await apiClient.post<OtcBlockTrade>('/otc', {
      ...req,
      amountIn: toRaw(req.amountIn),
    })
    return scaleOtcBlockTrade(data)
  },

  quote: async (id: string, req: QuoteOtcRequest): Promise<OtcBlockTrade> => {
    // amountOut is a human quantity; quotedPriceMicro is a price — leave it.
    const { data } = await apiClient.post<OtcBlockTrade>(`/otc/${id}/quote`, {
      ...req,
      amountOut: toRaw(req.amountOut),
    })
    return scaleOtcBlockTrade(data)
  },

  accept: async (id: string): Promise<OtcBlockTrade> => {
    const { data } = await apiClient.post<OtcBlockTrade>(`/otc/${id}/accept`)
    return scaleOtcBlockTrade(data)
  },

  reject: async (id: string, reason?: string): Promise<OtcBlockTrade> => {
    const { data } = await apiClient.post<OtcBlockTrade>(`/otc/${id}/reject`, { reason })
    return scaleOtcBlockTrade(data)
  },

  settle: async (id: string, req: SettleOtcRequest): Promise<OtcBlockTrade> => {
    const { data } = await apiClient.post<OtcBlockTrade>(`/otc/${id}/settle`, req)
    return scaleOtcBlockTrade(data)
  },

  cancel: async (id: string, reason?: string): Promise<OtcBlockTrade> => {
    const { data } = await apiClient.post<OtcBlockTrade>(`/otc/${id}/cancel`, { reason })
    return scaleOtcBlockTrade(data)
  },
}
