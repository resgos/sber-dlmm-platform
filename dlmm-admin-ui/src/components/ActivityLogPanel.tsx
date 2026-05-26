import { Card, List, Tag, Typography, Empty, Spin } from 'antd'
import { useQuery } from '@tanstack/react-query'
import apiClient from '@/api/client'
import dayjs from 'dayjs'

const { Text } = Typography

/**
 * QW-5 (Batch #5, Sprint 14) — reusable activity-log sidebar.
 *
 * <p>Drop this on any entity-detail page (User, Pool, Token,
 * Transaction) to expose the audit_log trail для that entity.
 * Compliance use case: "покажи мне каждое действие, которое
 * коснулось этого Tx" — answer в 1 second без копания в DB.
 *
 * <p>Reads from new {@code GET /api/v1/admin/audit-log?targetType=&targetId=}.
 * Empty state когда никаких log entries — common for newly-created
 * entities.
 */
interface AuditLogEntry {
  id: string
  action: string
  actorType: 'ADMIN' | 'USER' | null
  actorUserId: string | null
  actorRole: string | null
  targetType: string
  targetId: string
  status: 'SUCCESS' | 'FAILED' | string
  errorMessage: string | null
  methodSignature: string | null
  ipAddress: string | null
  createdAt: string
}

interface Props {
  targetType: string
  targetId: string
  limit?: number
  title?: string
}

export default function ActivityLogPanel({ targetType, targetId, limit = 20, title }: Props) {
  const { data, isLoading } = useQuery({
    queryKey: ['audit-log', targetType, targetId, limit],
    queryFn: async () => {
      const resp = await apiClient.get<AuditLogEntry[]>('/admin/audit-log', {
        params: { targetType, targetId, limit },
      })
      return resp.data
    },
    staleTime: 30_000,
    enabled: Boolean(targetType && targetId),
  })

  const entries: AuditLogEntry[] = data ?? []

  return (
    <Card
      className="sber-card"
      size="small"
      title={
        <Text strong style={{ fontSize: 13 }}>
          {title ?? 'Журнал действий'}
        </Text>
      }
    >
      {isLoading && <Spin style={{ display: 'block', margin: '24px auto' }} />}
      {!isLoading && entries.length === 0 && (
        <Empty
          description={
            <Text type="secondary" style={{ fontSize: 12 }}>
              Записей в журнале нет
            </Text>
          }
          imageStyle={{ height: 36 }}
        />
      )}
      {!isLoading && entries.length > 0 && (
        <List<AuditLogEntry>
          dataSource={entries}
          renderItem={(entry) => (
            <List.Item key={entry.id} style={{ padding: '8px 0' }}>
              <div style={{ width: '100%' }}>
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    gap: 8,
                    marginBottom: 4,
                  }}
                >
                  <Text strong style={{ fontSize: 12 }}>
                    {entry.action}
                  </Text>
                  <Tag
                    color={entry.status === 'SUCCESS' ? 'green' : entry.status === 'FAILED' ? 'red' : 'default'}
                    style={{ margin: 0, fontSize: 10, lineHeight: '16px', padding: '0 6px' }}
                  >
                    {entry.status}
                  </Tag>
                </div>
                <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>
                  {entry.actorType === 'ADMIN' ? '⚙️ ' : '👤 '}
                  {entry.actorUserId ? entry.actorUserId.substring(0, 8) + '…' : 'система'}
                  {entry.actorRole && ` · ${entry.actorRole}`}
                  {entry.ipAddress && ` · ${entry.ipAddress}`}
                </Text>
                <Text type="secondary" style={{ fontSize: 10 }}>
                  {dayjs(entry.createdAt).format('YYYY-MM-DD HH:mm:ss')}
                  {entry.methodSignature && ` · ${entry.methodSignature}`}
                </Text>
                {entry.errorMessage && (
                  <div style={{ marginTop: 4 }}>
                    <Text type="danger" style={{ fontSize: 11 }}>
                      {entry.errorMessage}
                    </Text>
                  </div>
                )}
              </div>
            </List.Item>
          )}
        />
      )}
    </Card>
  )
}
