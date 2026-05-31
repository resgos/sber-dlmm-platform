import apiClient from './client'
import type { TokenBalance } from './types'
import { scaleBalance } from './scale'

export const balances = {
  getMyBalances: async (): Promise<TokenBalance[]> => {
    const { data } = await apiClient.get<TokenBalance[]>('/balances/me')
    return data.map(scaleBalance)
  },

  getMyBalance: async (tokenId: string): Promise<TokenBalance> => {
    const { data } = await apiClient.get<TokenBalance>(`/balances/me/${tokenId}`)
    return scaleBalance(data)
  },
}
