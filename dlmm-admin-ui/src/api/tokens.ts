import apiClient from './client'
import type {
  Token,
  PageResponse,
  CreateTokenRequest,
  MintBurnRequest,
} from './types'

export const tokens = {
  getTokens: async (page = 0, size = 20): Promise<PageResponse<Token>> => {
    const response = await apiClient.get<PageResponse<Token>>('/admin/tokens', {
      params: { page, size },
    })
    return response.data
  },
  getToken: async (id: string): Promise<Token> => {
    const response = await apiClient.get<Token>(`/admin/tokens/${id}`)
    return response.data
  },
  createToken: async (data: CreateTokenRequest): Promise<Token> => {
    const response = await apiClient.post<Token>('/admin/tokens', data)
    return response.data
  },
  mint: async (id: string, data: MintBurnRequest): Promise<Token> => {
    const response = await apiClient.post<Token>(`/admin/tokens/${id}/mint`, data)
    return response.data
  },
  burn: async (id: string, data: MintBurnRequest): Promise<Token> => {
    const response = await apiClient.post<Token>(`/admin/tokens/${id}/burn`, data)
    return response.data
  },
}
