import apiClient from './client'
import type { Notification, PageResponse } from './types'

export const notifications = {
  // unreadOnly maps to the backend /me filter (default false) — used by the
  // full notifications page's "только непрочитанные" toggle.
  getMyNotifications: async (page = 0, size = 20, unreadOnly = false): Promise<PageResponse<Notification>> => {
    const { data } = await apiClient.get<PageResponse<Notification>>('/notifications/me', {
      params: unreadOnly ? { page, size, unreadOnly: true } : { page, size },
    })
    return data
  },

  markRead: async (id: string): Promise<void> => {
    await apiClient.post(`/notifications/${id}/read`)
  },

  // Pass a NotificationType to clear just that category (e.g. a noisy
  // MARGIN_WARNING pile); omit to clear everything.
  markAllRead: async (type?: string): Promise<void> => {
    await apiClient.post('/notifications/read-all', null, type ? { params: { type } } : undefined)
  },

  getUnreadCount: async (): Promise<number> => {
    const { data } = await apiClient.get<{ count: number }>('/notifications/me/unread-count')
    return data.count
  },

  // Unread counts keyed by NotificationType (e.g. { MARGIN_WARNING: 3, FEE_ACCRUED: 2 }),
  // for a categorised bell badge. Empty categories are omitted by the backend.
  getUnreadCountByType: async (): Promise<Record<string, number>> => {
    const { data } = await apiClient.get<Record<string, number>>('/notifications/me/unread-count-by-type')
    return data
  },
}
