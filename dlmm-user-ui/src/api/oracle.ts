import apiClient from './client'
import type { OhlcvCandle, TokenPrice } from './types'

export const oracle = {
  getPrices: async (): Promise<TokenPrice[]> => {
    const { data } = await apiClient.get<TokenPrice[]>('/oracle/prices')
    return data
  },

  getPrice: async (symbol: string): Promise<TokenPrice> => {
    const { data } = await apiClient.get<TokenPrice>(`/oracle/price/${symbol}`)
    return data
  },

  /**
   * Sprint 9-DS-r4 (P1-11/P1-4) — OHLCV candle series for the
   * TradingView-style chart on PoolDetailPage. Backend clamps `limit`
   * to [1,500] and only serves interval=60 (1m candles); higher
   * intervals are a Sprint 10 hourly roll-up.
   */
  getOhlcv: async (
    poolId: string,
    interval = 60,
    limit = 200,
  ): Promise<OhlcvCandle[]> => {
    const { data } = await apiClient.get<OhlcvCandle[]>(
      `/oracle/ohlcv/${poolId}`,
      { params: { interval, limit } },
    )
    return data
  },
}
