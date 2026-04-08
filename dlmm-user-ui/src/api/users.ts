import apiClient from './client'
import type { User, UpdateProfileRequest } from './types'

export const users = {
  getMe: async (): Promise<User> => {
    const { data } = await apiClient.get<User>('/users/me')
    return data
  },

  updateMe: async (req: UpdateProfileRequest): Promise<User> => {
    const { data } = await apiClient.put<User>('/users/me', req)
    return data
  },
}
