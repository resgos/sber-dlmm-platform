import apiClient from './client'
import type { TokenBalance } from './types'

export const balances = {
  getMyBalances: async (): Promise<TokenBalance[]> => {
    const { data } = await apiClient.get<TokenBalance[]>('/balances/me')
    return data
  },

  getMyBalance: async (tokenId: string): Promise<TokenBalance> => {
    const { data } = await apiClient.get<TokenBalance>(`/balances/me/${tokenId}`)
    return data
  },
}
