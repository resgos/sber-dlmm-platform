import { useState } from 'react'
import { List, Typography, Button, Empty, Space, Tag, Switch, Pagination, Tooltip } from 'antd'
import { CheckOutlined, ArrowRightOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/ru'
import { notifications as notificationsApi } from '@/api/services'
import { NOTIFICATION_TYPE_COLORS, NOTIFICATION_TAG_DEFAULT } from '@/styles/palette'
import { notificationDeepLink } from '@/lib/notificationLink'
import { PageHeader } from '@/components/sber'
import type { Notification as NotifType } from '@/api/types'

// Mirrors NotificationBell's relative-time setup: extend once, pick the locale
// per-render off the i18n INSTANCE (no global mutation) so "5 минут назад"
// follows the app language.
dayjs.extend(relativeTime)

const { Text } = Typography
const PAGE_SIZE = 15

/**
 * Full notification history. The header bell only surfaces the latest 10 — this
 * page is the "see all" surface: server-paginated over every notification, a
 * server-side unread-only toggle, a per-type unread breakdown with one-click
 * "mark this category read", and the same deep-linking as the bell (shared
 * notificationDeepLink) so any actionable notification opens its context.
 */
export default function NotificationsPage() {
  const { t, i18n } = useTranslation()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const dayjsLocale = i18n.language?.startsWith('en') ? 'en' : 'ru'
  const [page, setPage] = useState(0)
  const [unreadOnly, setUnreadOnly] = useState(false)

  const { data, isLoading } = useQuery({
    queryKey: ['notificationsPage', page, unreadOnly],
    queryFn: () => notificationsApi.getMyNotifications(page, PAGE_SIZE, unreadOnly),
  })
  const { data: byType } = useQuery({
    queryKey: ['unreadCountByType'],
    queryFn: notificationsApi.getUnreadCountByType,
  })

  // Invalidate every notification-derived query (this page, the header bell,
  // the badge counts) so a read here updates the bell immediately.
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['notificationsPage'] })
    qc.invalidateQueries({ queryKey: ['unreadCount'] })
    qc.invalidateQueries({ queryKey: ['unreadCountByType'] })
    qc.invalidateQueries({ queryKey: ['notifications'] })
  }
  const markReadMut = useMutation({ mutationFn: notificationsApi.markRead, onSuccess: invalidate })
  const markAllMut = useMutation({ mutationFn: (type?: string) => notificationsApi.markAllRead(type), onSuccess: invalidate })

  const totalUnread = byType ? Object.values(byType).reduce((s, c) => s + c, 0) : 0
  const items = data?.content ?? []
  const total = data?.totalElements ?? 0

  const typeLabel = (type: string) => t(`notifications.types.${type}`, { defaultValue: type.replace(/_/g, ' ') })

  const onItemClick = (item: NotifType) => {
    if (!item.isRead) markReadMut.mutate(item.id)
    const link = notificationDeepLink(item)
    if (link) navigate(link)
  }

  return (
    <Space direction="vertical" size={20} style={{ width: '100%' }}>
      <PageHeader title={t('notifications.page.title')} subtitle={t('notifications.page.subtitle')} />

      {/* Controls: unread-only toggle + global mark-all */}
      <Space wrap style={{ justifyContent: 'space-between', width: '100%' }}>
        <Space size={8}>
          <Switch checked={unreadOnly} onChange={(v) => { setUnreadOnly(v); setPage(0) }} />
          <Text>{t('notifications.page.unreadOnly')}</Text>
        </Space>
        <Button
          icon={<CheckOutlined />}
          onClick={() => markAllMut.mutate(undefined)}
          loading={markAllMut.isPending}
          disabled={totalUnread === 0}
        >
          {t('notifications.markAll')}
        </Button>
      </Space>

      {/* Per-type unread breakdown — each chip clears that category (server-side
          markAllRead(type)); the noisy MARGIN_WARNING pile can be dismissed in
          one click without scrolling. */}
      {byType && totalUnread > 0 && (
        <Space wrap size={8}>
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{t('notifications.page.byType')}</Text>
          {Object.entries(byType)
            .filter(([, count]) => count > 0)
            .sort((a, b) => b[1] - a[1])
            .map(([type, count]) => (
              <Tooltip key={type} title={t('notifications.page.clearType', { type: typeLabel(type) })}>
                <Tag
                  color={NOTIFICATION_TYPE_COLORS[type] || NOTIFICATION_TAG_DEFAULT}
                  style={{ cursor: 'pointer', borderRadius: 'var(--radius-pill)' }}
                  onClick={() => markAllMut.mutate(type)}
                >
                  {typeLabel(type)}: {count}
                </Tag>
              </Tooltip>
            ))}
        </Space>
      )}

      {!isLoading && items.length === 0 ? (
        <Empty
          description={unreadOnly ? t('notifications.page.emptyUnread') : t('notifications.empty')}
          image={Empty.PRESENTED_IMAGE_SIMPLE}
        />
      ) : (
        <>
          <List
            className="sber-table"
            loading={isLoading}
            dataSource={items}
            rowKey="id"
            renderItem={(item: NotifType) => {
              const link = notificationDeepLink(item)
              return (
                <List.Item
                  style={{
                    background: item.isRead ? 'transparent' : 'var(--sber-green-light)',
                    borderRadius: 'var(--radius-sm)',
                    padding: '12px 12px',
                    cursor: !item.isRead || link ? 'pointer' : 'default',
                  }}
                  onClick={() => onItemClick(item)}
                >
                  <Space direction="vertical" size={2} style={{ width: '100%' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                      <Space size={6}>
                        <Tag color={NOTIFICATION_TYPE_COLORS[item.type] || NOTIFICATION_TAG_DEFAULT} style={{ fontSize: 'var(--text-xs)', marginInlineEnd: 0 }}>
                          {typeLabel(item.type)}
                        </Tag>
                        {!item.isRead && (
                          <Tag color="blue" style={{ fontSize: 'var(--text-xs)', marginInlineEnd: 0, borderRadius: 'var(--radius-pill)' }}>
                            {t('notifications.page.unreadTag')}
                          </Tag>
                        )}
                      </Space>
                      <Text type="secondary" style={{ fontSize: 'var(--text-xs)', whiteSpace: 'nowrap' }}>
                        {dayjs(item.createdAt).locale(dayjsLocale).fromNow()}
                      </Text>
                    </div>
                    <Text strong style={{ fontSize: 'var(--text-sm)' }}>{item.title}</Text>
                    <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{item.message}</Text>
                    {link && (
                      <Button
                        type="link"
                        size="small"
                        icon={<ArrowRightOutlined />}
                        style={{ padding: 0, height: 'auto', marginTop: 2, fontSize: 'var(--text-xs)' }}
                        onClick={(e) => { e.stopPropagation(); onItemClick(item) }}
                      >
                        {t('notifications.open')}
                      </Button>
                    )}
                  </Space>
                </List.Item>
              )
            }}
          />
          {total > PAGE_SIZE && (
            <div style={{ textAlign: 'right' }}>
              <Pagination
                current={page + 1}
                pageSize={PAGE_SIZE}
                total={total}
                showSizeChanger={false}
                onChange={(p) => setPage(p - 1)}
                showTotal={(tot) => t('notifications.page.totalCount', { count: tot })}
              />
            </div>
          )}
        </>
      )}
    </Space>
  )
}
