import apiClient from './client'
import type { TokenPrice } from './types'

export const oracle = {
  getPrices: async (): Promise<TokenPrice[]> => {
    const { data } = await apiClient.get<TokenPrice[]>('/oracle/prices')
    return data
  },

  getPrice: async (symbol: string): Promise<TokenPrice> => {
    const { data } = await apiClient.get<TokenPrice>(`/oracle/price/${symbol}`)
    return data
  },
}
