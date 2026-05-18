import { useState } from 'react'
import { Badge, Popover, List, Typography, Button, Empty, Space, Tag } from 'antd'
import { BellOutlined, CheckOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { notifications as notificationsApi } from '@/api/services'
import type { Notification as NotifType } from '@/api/types'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/ru'

dayjs.extend(relativeTime)
dayjs.locale('ru')

const { Text } = Typography

const typeColors: Record<string, string> = {
  SWAP_COMPLETED: '#21A038',
  LIQUIDITY_ADDED: '#3B82F6',
  FEE_ACCRUED: '#F59E0B',
  KYC_APPROVED: '#21A038',
  KYC_REJECTED: '#EF4444',
  POSITION_CLOSED: '#6B7280',
  POOL_PAUSED: '#F59E0B',
  SYSTEM_ALERT: '#EF4444',
  POOL_UPDATE: '#8B5CF6',
  // Sprint 5 #5.15 — margin alerts. WARNING = amber (treasurer should
  // look soon), CALL = red (position is out-of-range, fees not accruing).
  MARGIN_WARNING: '#F59E0B',
  MARGIN_CALL: '#DC2626',
}

const typeRussianLabels: Record<string, string> = {
  SWAP_COMPLETED: 'Своп',
  LIQUIDITY_ADDED: 'Ликвидность',
  FEE_ACCRUED: 'Комиссии',
  KYC_APPROVED: 'KYC ✓',
  KYC_REJECTED: 'KYC ✗',
  POSITION_CLOSED: 'Позиция закрыта',
  POOL_PAUSED: 'Пул приостановлен',
  SYSTEM_ALERT: 'Система',
  POOL_UPDATE: 'Пул',
  MARGIN_WARNING: '⚠ Маржин-вотчинг',
  MARGIN_CALL: '🔴 Маржин-колл',
}

export default function NotificationBell() {
  const [open, setOpen] = useState(false)
  const queryClient = useQueryClient()

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
    mutationFn: notificationsApi.markAllRead,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notifications'] })
      queryClient.invalidateQueries({ queryKey: ['unreadCount'] })
    },
  })

  const content = (
    <div style={{ width: 360 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <Text strong style={{ fontSize: 15 }}>Уведомления</Text>
        {unreadCount > 0 && (
          <Button
            type="link"
            size="small"
            icon={<CheckOutlined />}
            onClick={() => markAllMut.mutate()}
            loading={markAllMut.isPending}
          >
            Прочитать все
          </Button>
        )}
      </div>
      {!notifs?.content?.length ? (
        <Empty description="Нет уведомлений" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <List
          dataSource={notifs.content}
          style={{ maxHeight: 400, overflow: 'auto' }}
          renderItem={(item: NotifType) => (
            <List.Item
              style={{
                padding: '10px 0',
                background: item.isRead ? 'transparent' : '#F0FFF4',
                borderRadius: 8,
                paddingLeft: 8,
                paddingRight: 8,
                cursor: item.isRead ? 'default' : 'pointer',
              }}
              onClick={() => {
                if (!item.isRead) markReadMut.mutate(item.id)
              }}
            >
              <Space direction="vertical" size={2} style={{ width: '100%' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <Tag color={typeColors[item.type] || '#6B7280'} style={{ fontSize: 11 }}>
                    {typeRussianLabels[item.type] || item.type.replace(/_/g, ' ')}
                  </Tag>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    {dayjs(item.createdAt).fromNow()}
                  </Text>
                </div>
                <Text strong style={{ fontSize: 13 }}>{item.title}</Text>
                <Text type="secondary" style={{ fontSize: 12 }}>{item.message}</Text>
              </Space>
            </List.Item>
          )}
        />
      )}
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
      <Badge count={unreadCount} size="small" offset={[-2, 2]}>
        <BellOutlined style={{ fontSize: 20, color: '#6B7280', cursor: 'pointer' }} />
      </Badge>
    </Popover>
  )
}
