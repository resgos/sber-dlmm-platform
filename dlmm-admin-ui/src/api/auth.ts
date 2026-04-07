import apiClient from './client'
import type { AuthResponse } from './types'

export const auth = {
  login: async (email: string, password: string): Promise<AuthResponse> => {
    const response = await apiClient.post<AuthResponse>('/auth/login', { email, password })
    return response.data
  },
  refreshToken: async (token: string): Promise<AuthResponse> => {
    const response = await apiClient.post<AuthResponse>('/auth/refresh', { refreshToken: token })
    return response.data
  },
}
