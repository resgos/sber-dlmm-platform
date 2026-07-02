import { useMemo } from 'react'
import {
  Table,
  Space,
  Tag,
  Card,
  Alert,
  Spin,
  Tooltip,
  Button,
  message,
} from 'antd'
import {
  WarningOutlined,
  SafetyOutlined,
  CheckCircleOutlined,
  RiseOutlined,
  CheckOutlined,
  DownloadOutlined,
} from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import { transactions as txService } from '@/api/services'
import type { SuspiciousTransaction } from '@/api/types'
import dayjs from 'dayjs'
import { KpiRow, PageHeader, UserChip } from '@/components/sber'
import { formatCompact, shortId } from '@/lib/format'
import { exportToCsv, type CsvColumn } from '@/lib/csvExport'

// Compliance export schema — kept module-level (and exported) so the column
// mapping is unit-testable without rendering the page. Typed against
// SuspiciousTransaction so a wrong field name is a compile error.
export const suspiciousCsvColumns: CsvColumn<SuspiciousTransaction>[] = [
  { header: 'ID', accessor: (t) => t.id },
  { header: 'Тип', accessor: (t) => t.type },
  { header: 'ID пользователя', accessor: (t) => t.userId },
  { header: 'ID пула', accessor: (t) => t.poolId ?? '' },
  { header: 'Сумма', accessor: (t) => t.amount },
  { header: 'Причина', accessor: (t) => t.reason },
  { header: 'Время', accessor: (t) => dayjs(t.timestamp).format('YYYY-MM-DD HH:mm:ss') },
]

/**
 * Sprint 9-DS — Suspicious transactions page refactor.
 * - replaced raw "id.slice(0,8)" with shared `shortId` helper
 * - userId column now renders the avatar/email UserChip
 * - added 4-up KPI strip (Total, today, big-ticket >1M, distinct users)
 * - empty state moved into the table component (Empty fallback) so the
 *   green success banner doesn't shout when there's genuinely nothing
 *   to look at
 */
export default function SuspiciousTransactionsPage() {
  const queryClient = useQueryClient()
  const { data, isLoading, error } = useQuery({
    queryKey: ['suspiciousTransactions'],
    queryFn: txService.getSuspiciousTransactions,
    refetchInterval: 30000,
  })

  // Sprint 9-DS-r4 (P2-12) — "Mark reviewed" mutation. Stamps
  // reviewedAt/reviewedBy on the transaction; admin-bff's
  // suspicious-detection skips reviewed rows, so the row vanishes
  // from this list on the next refetch (we invalidate immediately
  // for instant feedback).
  const reviewMutation = useMutation({
    mutationFn: (txId: string) => txService.markReviewed(txId),
    onSuccess: () => {
      message.success('Транзакция помечена как просмотренная')
      queryClient.invalidateQueries({ queryKey: ['suspiciousTransactions'] })
    },
    onError: () => message.error('Не удалось пометить транзакцию'),
  })

  const summary = useMemo(() => {
    const list = data ?? []
    const today = dayjs().startOf('day')
    const todayCount = list.filter((t) => dayjs(t.timestamp).isAfter(today)).length
    const bigTicket = list.filter((t) => t.amount > 1_000_000).length
    const distinctUsers = new Set(list.map((t) => t.userId)).size
    return { total: list.length, today: todayCount, bigTicket, distinctUsers }
  }, [data])

  // Compliance export — a regulator/AML officer asks "give me every flagged
  // operation": one click downloads the full current list. Columns typed
  // against SuspiciousTransaction so a wrong field name is a compile error.
  const handleExportCsv = () => {
    const list = data ?? []
    if (list.length === 0) return
    const stamp = dayjs().format('YYYY-MM-DD')
    exportToCsv(`dlmm-suspicious-${stamp}.csv`, list, suspiciousCsvColumns)
  }

  const columns: ColumnsType<SuspiciousTransaction> = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 110,
      render: (id: string) => (
        <Tooltip title={id}>
          <span style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12, color: 'var(--text-secondary)' }}>
            {shortId(id)}
          </span>
        </Tooltip>
      ),
    },
    {
      title: 'Тип',
      dataIndex: 'type',
      key: 'type',
      width: 120,
      // 2026-06-17 — the BFF now sends txType (mapped to `type` at the API
      // boundary); em-dash instead of an empty blue chip when it's absent.
      render: (type: string) => (type ? <Tag color="blue">{type}</Tag> : '—'),
    },
    {
      title: 'Пользователь',
      dataIndex: 'userId',
      key: 'userId',
      width: 220,
      render: (id: string) => <UserChip userId={id} />,
    },
    {
      title: 'ID пула',
      dataIndex: 'poolId',
      key: 'poolId',
      width: 120,
      render: (id: string | null) =>
        id ? (
          <Tooltip title={id}>
            <span style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12, color: 'var(--text-secondary)' }}>
              {shortId(id)}
            </span>
          </Tooltip>
        ) : (
          '—'
        ),
    },
    {
      title: 'Сумма',
      dataIndex: 'amount',
      key: 'amount',
      align: 'right',
      render: (val: number) => (
        <span style={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
          {formatCompact(val)}
        </span>
      ),
      sorter: (a, b) => a.amount - b.amount,
    },
    {
      title: 'Причина',
      dataIndex: 'reason',
      key: 'reason',
      render: (reason: string) => (
        <Tooltip title={reason}>
          <Tag color="red" icon={<WarningOutlined />}>
            {reason.length > 40 ? `${reason.slice(0, 40)}…` : reason}
          </Tag>
        </Tooltip>
      ),
    },
    {
      title: 'Время',
      dataIndex: 'timestamp',
      key: 'timestamp',
      width: 150,
      render: (date: string) => (
        <span style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12 }}>
          {dayjs(date).format('DD.MM.YYYY HH:mm')}
        </span>
      ),
      sorter: (a, b) => dayjs(a.timestamp).unix() - dayjs(b.timestamp).unix(),
      defaultSortOrder: 'descend',
    },
    {
      // Sprint 9-DS-r4 (P2-12) — per-row "Просмотрено" action.
      // Stamps reviewedAt on the transaction; the row disappears on
      // the next refetch.
      title: 'Действие',
      key: 'review',
      width: 130,
      render: (_: unknown, r: SuspiciousTransaction) => (
        <Button
          size="small"
          type="primary"
          ghost
          icon={<CheckOutlined />}
          loading={reviewMutation.isPending && reviewMutation.variables === r.id}
          onClick={() => reviewMutation.mutate(r.id)}
        >
          Просмотрено
        </Button>
      ),
    },
  ]

  if (isLoading) {
    return (
      <div style={{ textAlign: 'center', padding: '80px' }}>
        <Spin size="large" tip="Загрузка подозрительных транзакций..." />
      </div>
    )
  }

  if (error) {
    return (
      <Alert
        message="Не удалось загрузить подозрительные транзакции"
        type="error"
        showIcon
        style={{ borderRadius: 8 }}
      />
    )
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="Подозрительные транзакции"
        subtitle="Автоматически выявленные операции с признаками аномалий — обновляется каждые 30 сек"
        status={
          summary.total > 0 ? (
            <Tag color="red" style={{ borderRadius: 12, padding: '2px 10px', fontWeight: 600 }}>
              {summary.total} выявлено
            </Tag>
          ) : (
            <Tag color="green" style={{ borderRadius: 12, padding: '2px 10px', fontWeight: 600 }}>
              <CheckCircleOutlined /> чисто
            </Tag>
          )
        }
      />

      <KpiRow
        tiles={[
          {
            label: 'Всего флагов',
            value: summary.total.toLocaleString('ru-RU'),
            sub: 'за последние 24ч',
            icon: <SafetyOutlined style={{ color: summary.total > 0 ? '#D14D00' : 'var(--text-muted)' }} />,
            accent: summary.total > 0 ? '#D14D00' : undefined,
          },
          {
            label: 'Сегодня',
            value: summary.today.toLocaleString('ru-RU'),
            sub: summary.today === 0 ? 'новых нет' : 'требуют разбора',
            icon: <WarningOutlined style={{ color: summary.today > 0 ? '#DC2626' : 'var(--text-muted)' }} />,
            accent: summary.today > 0 ? '#DC2626' : undefined,
          },
          {
            label: 'Крупные (>1M)',
            value: summary.bigTicket.toLocaleString('ru-RU'),
            sub: 'high-value alerts',
            icon: <RiseOutlined style={{ color: summary.bigTicket > 0 ? '#9B59B6' : 'var(--text-muted)' }} />,
          },
          {
            label: 'Затронуто пользователей',
            value: summary.distinctUsers.toLocaleString('ru-RU'),
            sub: 'уникальных аккаунтов',
            icon: <CheckCircleOutlined style={{ color: '#296AE3' }} />,
          },
        ]}
      />

      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button
          icon={<DownloadOutlined />}
          onClick={handleExportCsv}
          disabled={(data?.length ?? 0) === 0}
        >
          Экспорт CSV
        </Button>
      </div>

      <Card className="sber-card sber-table" style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}>
        <Table<SuspiciousTransaction>
          columns={columns}
          dataSource={data}
          rowKey="id"
          loading={isLoading}
          pagination={{
            pageSize: 20,
            showSizeChanger: true,
            showTotal: (total) => `Всего ${total} флагов`,
          }}
          rowClassName={(record) =>
            record.amount > 1000000 ? 'ant-table-row-danger' : ''
          }
          locale={{
            emptyText: (
              <div style={{ padding: '40px 0', textAlign: 'center' }}>
                <CheckCircleOutlined style={{ fontSize: 32, color: 'var(--sber-green)', marginBottom: 8 }} />
                <div style={{ fontSize: 14, fontWeight: 500, color: 'var(--text-primary)' }}>
                  Подозрительных транзакций не обнаружено
                </div>
                <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
                  Платформа работает в штатном режиме. Автообновление каждые 30 сек.
                </div>
              </div>
            ),
          }}
          size="middle"
        />
      </Card>
    </Space>
  )
}
