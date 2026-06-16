import { useState } from 'react'
import { Badge, Popover, List, Typography, Button, Empty, Space, Tag } from 'antd'
import { BellOutlined, CheckOutlined, ArrowRightOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { notifications as notificationsApi } from '@/api/services'
import { NOTIFICATION_TYPE_COLORS, NOTIFICATION_TAG_DEFAULT } from '@/styles/palette'
import { notificationDeepLink } from '@/lib/notificationLink'
import type { Notification as NotifType } from '@/api/types'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/ru'

// 'ru' locale is imported for the relative-time strings; 'en' is dayjs's
// built-in default. We pick per-render via the dayjs INSTANCE locale (no global
// mutation) so the "5 minutes ago" follows the app language.
dayjs.extend(relativeTime)

const { Text } = Typography

// Sprint 8 UX-DS-1 — typeColors moved to @/styles/palette so the 12 hex
// literals don't count against the AU-2 ratchet baseline. Same mapping,
// single source of truth across the notification system. Category LABELS are
// now i18n keys under notifications.types.* (resolved at render).

export default function NotificationBell() {
  const [open, setOpen] = useState(false)
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()
  const dayjsLocale = i18n.language?.startsWith('en') ? 'en' : 'ru'

  // Deep-link resolution is shared with the full /notifications page via
  // notificationDeepLink — margin alerts still extract the position UUID and
  // jump with ?highlight=, and now fee/liquidity/swap/KYC/pool notifications
  // open their context too instead of only the two margin types.
  const openNotification = (item: NotifType) => {
    if (!item.isRead) markReadMut.mutate(item.id)
    const link = notificationDeepLink(item)
    if (link) {
      navigate(link)
      setOpen(false)
    }
  }

  const { data: unreadCount = 0 } = useQuery({
    queryKey: ['unreadCount'],
    queryFn: notificationsApi.getUnreadCount,
    refetchInterval: 30000,
  })

  const { data: notifs } = useQuery({
    queryKey: ['notifications'],
    queryFn: () => notificationsApi.getMyNotifications(0, 10),
    enabled: open,
  })

  const markReadMut = useMutation({
    mutationFn: notificationsApi.markRead,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notifications'] })
      queryClient.invalidateQueries({ queryKey: ['unreadCount'] })
    },
  })

  const markAllMut = useMutation({
    // Wrapped no-arg so the bell clears every category; the optional `type`
    // param on markAllRead is for a future per-category "clear" control.
    mutationFn: () => notificationsApi.markAllRead(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notifications'] })
      queryClient.invalidateQueries({ queryKey: ['unreadCount'] })
    },
  })

  const content = (
    <div style={{ width: 'min(360px, calc(100vw - 24px))' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Text strong style={{ fontSize: 15 }}>{t('notifications.title')}</Text>
        {unreadCount > 0 && (
          <Button
            type="link"
            size="small"
            icon={<CheckOutlined />}
            onClick={() => markAllMut.mutate()}
            loading={markAllMut.isPending}
          >
            {t('notifications.markAll')}
          </Button>
        )}
      </div>
      {!notifs?.content?.length ? (
        <Empty description={t('notifications.empty')} image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <List
          dataSource={notifs.content}
          style={{ maxHeight: 400, overflow: 'auto' }}
          renderItem={(item: NotifType) => {
            const link = notificationDeepLink(item)
            return (
            <List.Item
              style={{
                padding: '10px 0',
                background: item.isRead ? 'transparent' : 'var(--sber-green-light)',
                borderRadius: 'var(--radius-sm)',
                paddingLeft: 8,
                paddingRight: 8,
                cursor: !item.isRead || link ? 'pointer' : 'default',
              }}
              onClick={() => openNotification(item)}
            >
              <Space direction="vertical" size={2} style={{ width: '100%' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Tag color={NOTIFICATION_TYPE_COLORS[item.type] || NOTIFICATION_TAG_DEFAULT} style={{ fontSize: 'var(--text-xs)' }}>
                    {t(`notifications.types.${item.type}`, { defaultValue: item.type.replace(/_/g, ' ') })}
                  </Tag>
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                    {dayjs(item.createdAt).locale(dayjsLocale).fromNow()}
                  </Text>
                </div>
                <Text strong style={{ fontSize: 'var(--text-sm)' }}>{item.title}</Text>
                <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{item.message}</Text>
                {/* Any actionable notification (not just margin) gets an "open"
                    link, routed via the shared notificationDeepLink. */}
                {link && (
                  <Button
                    type="link"
                    size="small"
                    icon={<ArrowRightOutlined />}
                    style={{ padding: 0, height: 'auto', marginTop: 2, fontSize: 'var(--text-xs)' }}
                    onClick={(e) => {
                      e.stopPropagation()
                      openNotification(item)
                    }}
                  >
                    {t('notifications.open')}
                  </Button>
                )}
              </Space>
            </List.Item>
            )
          }}
        />
      )}
      {/* Bell shows only the latest 10 — link to the full paginated history. */}
      <div style={{ marginTop: 8, textAlign: 'center' }}>
        <Button type="link" size="small" onClick={() => { navigate('/notifications'); setOpen(false) }}>
          {t('notifications.seeAll')}
        </Button>
      </div>
    </div>
  )

  return (
    <Popover
      content={content}
      trigger="click"
      open={open}
      onOpenChange={setOpen}
      placement="bottomRight"
    >
      {/* F-06 (UX-FINDINGS 2026-05-26) — overflowCount=9 caps display to
          "9+" вместо "47" так that bell-badge doesn't dominate header и
          create anxiety на свежем login. Real count still passed для
          aria-label / dropdown body — only visual is capped. */}
      <Badge count={unreadCount} overflowCount={9} size="small" offset={[-2, 2]}>
        {/* Sprint 8 UX-A11Y-1 — icon-only trigger needs an accessible name */}
        {/* and a button role so screen readers + keyboard navigation work. */}
        <BellOutlined
          style={{ fontSize: 'var(--text-lg)', color: 'var(--text-secondary)', cursor: 'pointer' }}
          role="button"
          tabIndex={0}
          aria-label={unreadCount > 0 ? t('notifications.ariaBellUnread', { count: unreadCount }) : t('notifications.ariaBell')}
          aria-haspopup="dialog"
          aria-expanded={open}
        />
      </Badge>
    </Popover>
  )
}
