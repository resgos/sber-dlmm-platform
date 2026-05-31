import apiClient from './client'
import type { OhlcvCandle, TokenPrice } from './types'
import { scaleOhlcv } from './scale'

/** Backend price-oracle wire shape (com.sber.dlmm.oracle.dto.PriceFeedResponse). */
interface RawPriceFeed {
  id: string
  assetSymbol: string
  source: string
  currentPrice: number | string
  twapPrice: number | string
  priceChange24hPct: number | string
}

// The oracle serves assetSymbol/currentPrice/priceChange24hPct, but the UI's
// TokenPrice expects symbol/price/change24h. Without this map the UI read every
// field as `undefined` and silently fell back to pool prices — which is exactly
// why real exchange prices never showed. Prices are RUB ratios, NOT token
// amounts, so they are intentionally NOT run through the 1e-4 amount scale.
const toTokenPrice = (d: RawPriceFeed): TokenPrice => ({
  tokenId: d.id,
  symbol: d.assetSymbol,
  price: Number(d.currentPrice),
  change24h: Number(d.priceChange24hPct),
  source: d.source,
})

export const oracle = {
  getPrices: async (): Promise<TokenPrice[]> => {
    const { data } = await apiClient.get<RawPriceFeed[]>('/oracle/prices')
    return data.map(toTokenPrice)
  },

  getPrice: async (symbol: string): Promise<TokenPrice> => {
    const { data } = await apiClient.get<RawPriceFeed>(`/oracle/price/${symbol}`)
    return toTokenPrice(data)
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
    return data.map(scaleOhlcv)
  },
}
