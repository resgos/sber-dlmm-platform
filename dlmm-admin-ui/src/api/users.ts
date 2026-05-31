import apiClient from './client'
import type {
  User,
  Transaction,
  PageResponse,
  KycStatus,
  UserRole,
} from './types'
import { scaleTransaction } from './scale'

export const users = {
  getUsers: async (page = 0, size = 20, email?: string): Promise<PageResponse<User>> => {
    const params: Record<string, unknown> = { page, size }
    if (email) params.email = email
    const response = await apiClient.get<PageResponse<User>>('/admin/users', { params })
    return response.data
  },
  getUser: async (id: string): Promise<User> => {
    const response = await apiClient.get<User>(`/admin/users/${id}`)
    return response.data
  },
  updateKyc: async (id: string, status: KycStatus): Promise<User> => {
    const response = await apiClient.put<User>(`/admin/users/${id}/kyc`, { status })
    return response.data
  },
  updateRole: async (id: string, role: UserRole): Promise<User> => {
    const response = await apiClient.put<User>(`/admin/users/${id}/role`, { role })
    return response.data
  },
  blockUser: async (id: string): Promise<User> => {
    const response = await apiClient.post<User>(`/admin/users/${id}/block`)
    return response.data
  },
  unblockUser: async (id: string): Promise<User> => {
    const response = await apiClient.post<User>(`/admin/users/${id}/unblock`)
    return response.data
  },
  getUserTransactions: async (
    id: string,
    page = 0,
    size = 20,
  ): Promise<PageResponse<Transaction>> => {
    const response = await apiClient.get<PageResponse<Transaction>>(
      `/admin/users/${id}/transactions`,
      { params: { page, size } },
    )
    return { ...response.data, content: response.data.content.map(scaleTransaction) }
  },
}
