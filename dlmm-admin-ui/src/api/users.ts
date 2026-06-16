import apiClient from './client'
import type {
  User,
  Transaction,
  PageResponse,
  KycStatus,
  UserRole,
} from './types'
import { scaleTransaction } from './scale'

// The user-service / admin-bff user payload carries firstName + lastName but
// NOT a composed fullName (verified against the live API: keys are id, sberId,
// email, phone, firstName, lastName, kycStatus, role, createdAt, lastLoginAt).
// The UI reads user.fullName everywhere (users table, detail header, OTC
// counterparty picker) and was silently falling back to email — so real names
// never showed. Compose it once here at the API boundary so every consumer
// gets the actual name without each having to join the parts.
function normalizeUser(u: User): User {
  const composed = [u.firstName, u.lastName].filter(Boolean).join(' ').trim()
  return { ...u, fullName: u.fullName || composed || undefined }
}

export const users = {
  // Free-text name/email search. The param MUST be `query`: admin-bff (and
  // user-service) read `query`, so sending `email` silently dropped the filter
  // and the admin search returned everyone regardless of input.
  getUsers: async (page = 0, size = 20, query?: string): Promise<PageResponse<User>> => {
    const params: Record<string, unknown> = { page, size }
    if (query) params.query = query
    const response = await apiClient.get<PageResponse<User>>('/admin/users', { params })
    return { ...response.data, content: response.data.content.map(normalizeUser) }
  },
  getUser: async (id: string): Promise<User> => {
    const response = await apiClient.get<User>(`/admin/users/${id}`)
    return normalizeUser(response.data)
  },
  updateKyc: async (id: string, status: KycStatus): Promise<User> => {
    const response = await apiClient.put<User>(`/admin/users/${id}/kyc`, { status })
    return normalizeUser(response.data)
  },
  updateRole: async (id: string, role: UserRole): Promise<User> => {
    const response = await apiClient.put<User>(`/admin/users/${id}/role`, { role })
    return normalizeUser(response.data)
  },
  blockUser: async (id: string): Promise<User> => {
    const response = await apiClient.post<User>(`/admin/users/${id}/block`)
    return normalizeUser(response.data)
  },
  unblockUser: async (id: string): Promise<User> => {
    const response = await apiClient.post<User>(`/admin/users/${id}/unblock`)
    return normalizeUser(response.data)
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
