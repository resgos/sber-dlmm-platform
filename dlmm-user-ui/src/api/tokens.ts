import apiClient from './client'
import type { Token, PageResponse } from './types'

export const tokens = {
  getTokens: async (page = 0, size = 100): Promise<PageResponse<Token>> => {
    const { data } = await apiClient.get<PageResponse<Token>>('/tokens', {
      params: { page, size },
    })
    return data
  },

  getToken: async (id: string): Promise<Token> => {
    const { data } = await apiClient.get<Token>(`/tokens/${id}`)
    return data
  },
}
