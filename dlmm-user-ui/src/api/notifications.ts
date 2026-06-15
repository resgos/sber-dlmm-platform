import apiClient from './client'
import type { Notification, PageResponse } from './types'

export const notifications = {
  getMyNotifications: async (page = 0, size = 20): Promise<PageResponse<Notification>> => {
    const { data } = await apiClient.get<PageResponse<Notification>>('/notifications/me', {
      params: { page, size },
    })
    return data
  },

  markRead: async (id: string): Promise<void> => {
    await apiClient.post(`/notifications/${id}/read`)
  },

  markAllRead: async (): Promise<void> => {
    await apiClient.post('/notifications/read-all')
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
