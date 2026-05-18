import apiClient from './client'

/**
 * Sprint 6 #6.7 — 115-ФЗ самозапрет API client.
 * Backend: dlmm-user-service (`/api/v1/users/me/self-restriction/*`).
 */

export type RestrictionAction = 'SET' | 'LIFT_REQUESTED' | 'LIFTED'

export interface SelfRestrictionRow {
  id: string
  userId: string
  action: RestrictionAction
  scope: 'ALL_NEW_POSITIONS'
  reason: string | null
  createdAt: string
  effectiveAt: string
}

export interface SelfRestrictionStatus {
  active: boolean
  history: SelfRestrictionRow[]
}

export const selfRestriction = {
  getStatus: async (): Promise<SelfRestrictionStatus> => {
    const { data } = await apiClient.get<SelfRestrictionStatus>('/users/me/self-restriction')
    return data
  },

  set: async (reason?: string): Promise<SelfRestrictionRow> => {
    const { data } = await apiClient.post<SelfRestrictionRow>(
      '/users/me/self-restriction/set',
      { reason: reason || 'Установлен пользователем' },
    )
    return data
  },

  requestLift: async (reason?: string): Promise<SelfRestrictionRow> => {
    const { data } = await apiClient.post<SelfRestrictionRow>(
      '/users/me/self-restriction/lift-request',
      { reason: reason || 'Запрос пользователя' },
    )
    return data
  },

  finaliseLift: async (): Promise<SelfRestrictionRow> => {
    const { data } = await apiClient.post<SelfRestrictionRow>(
      '/users/me/self-restriction/lift-finalise',
    )
    return data
  },
}
