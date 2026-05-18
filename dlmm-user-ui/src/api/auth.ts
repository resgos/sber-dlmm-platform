import apiClient from './client'
import type { AuthResponse, RegisterRequest } from './types'

export const auth = {
  login: async (email: string, password: string): Promise<AuthResponse> => {
    const { data } = await apiClient.post<AuthResponse>('/auth/login', { email, password })
    return data
  },

  register: async (req: RegisterRequest): Promise<AuthResponse> => {
    const { data } = await apiClient.post<AuthResponse>('/auth/register', req)
    return data
  },

  refreshToken: async (refreshToken: string): Promise<AuthResponse> => {
    const { data } = await apiClient.post<AuthResponse>('/auth/refresh', { refreshToken })
    return data
  },

  /**
   * Sprint 8 AU-3 — server-side JWT revocation. POSTing to /auth/logout
   * adds the bearer token's jti to the Redis denylist; subsequent requests
   * with that token return 401. Always returns 204 (idempotent) so this
   * call is safe even if the token is already invalid or revoked.
   */
  logout: async (refreshToken?: string): Promise<void> => {
    // Best-effort: if the server is down we still want the local
    // authStore.logout() (caller's responsibility) to fire so the user
    // isn't trapped on a broken session.
    try {
      await apiClient.post('/auth/logout', refreshToken ? { refreshToken } : {})
    } catch {
      /* swallow — local logout still happens */
    }
  },
}
