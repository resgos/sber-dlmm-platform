import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Typography, Card, Table, Tag, Button, Popconfirm, Segmented, Empty, message } from 'antd'
import { AimOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { limitOrders } from '@/api/services'
import type { LimitOrder, LimitOrderStatus } from '@/api/types'
import { formatTokenAmount } from '@/lib/format'

const { Title, Text } = Typography

type Filter = 'ALL' | LimitOrderStatus

// Status → tag colour. Labels are resolved at render via t('orders.status.<KEY>').
const STATUS_COLOR: Record<LimitOrderStatus, string> = {
  OPEN: 'processing',
  FILLED: 'success',
  CANCELLED: 'default',
}

/**
 * Sprint 16 (Meteora parity) — top-level limit-order management. Mirrors
 * Meteora's "Limit Orders / History": every order across all pools, filterable
 * by status, with one-click cancel (refunds the escrow) on open ones. Placing an
 * order happens in-context on the pool page (PoolActionTabs → «Лимит»).
 */
export default function OrdersPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [filter, setFilter] = useState<Filter>('ALL')

  const { data: orders, isLoading } = useQuery({
    queryKey: ['myLimitOrders', 'all'],
    queryFn: () => limitOrders.getMine({}),
  })

  const cancelMutation = useMutation({
    mutationFn: (id: string) => limitOrders.cancel(id),
    onSuccess: () => {
      message.success(t('orders.messages.cancelSuccess'))
      queryClient.invalidateQueries({ queryKey: ['myLimitOrders'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
    },
    onError: () => message.error(t('orders.messages.cancelError')),
  })

  const filtered = useMemo(() => {
    const all = orders ?? []
    return filter === 'ALL' ? all : all.filter((o) => o.status === filter)
  }, [orders, filter])

  const columns: ColumnsType<LimitOrder> = [
    {
      title: t('orders.table.pool'),
      key: 'pool',
      render: (_, o) => {
        const base = o.side === 'BUY' ? o.tokenOutSymbol : o.tokenInSymbol
        const quote = o.side === 'BUY' ? o.tokenInSymbol : o.tokenOutSymbol
        return (
          <Button type="link" style={{ padding: 0, fontWeight: 600 }} onClick={() => navigate(`/pools/${o.poolId}`)}>
            {(base ?? '—')}/{(quote ?? '—')}
          </Button>
        )
      },
    },
    {
      title: t('orders.table.side'),
      dataIndex: 'side',
      key: 'side',
      render: (side: LimitOrder['side']) => (
        <Tag color={side === 'BUY' ? 'green' : 'volcano'} style={{ margin: 0, fontWeight: 600 }}>
          {side === 'BUY' ? t('orders.side.buy') : t('orders.side.sell')}
        </Tag>
      ),
    },
    {
      title: t('orders.table.amountIn'),
      key: 'in',
      render: (_, o) => (
        <Text style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatTokenAmount(o.amountIn, o.tokenInSymbol ?? '', { compact: true })}
        </Text>
      ),
    },
    {
      title: t('orders.table.price'),
      key: 'price',
      render: (_, o) => (
        <Text type="secondary" style={{ fontVariantNumeric: 'tabular-nums' }}>
          {o.limitPrice.toLocaleString('ru-RU', { maximumFractionDigits: 6 })}
        </Text>
      ),
    },
    {
      title: t('orders.table.amountOut'),
      key: 'out',
      render: (_, o) => (
        <Text strong style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatTokenAmount(o.amountOut, o.tokenOutSymbol ?? '', { compact: true })}
        </Text>
      ),
    },
    {
      title: t('orders.table.status'),
      dataIndex: 'status',
      key: 'status',
      render: (status: LimitOrderStatus) => {
        const color = STATUS_COLOR[status] ?? 'default'
        const label = t(`orders.status.${status}`, { defaultValue: String(status) })
        return <Tag color={color} style={{ margin: 0 }}>{label}</Tag>
      },
    },
    {
      title: t('orders.table.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      responsive: ['md'],
      render: (d: string) => (
        <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
          {new Date(d).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })}
        </Text>
      ),
    },
    {
      title: '',
      key: 'action',
      align: 'right',
      render: (_, o) =>
        o.status === 'OPEN' ? (
          <Popconfirm
            title={t('orders.cancelConfirm.title')}
            description={t('orders.cancelConfirm.description')}
            okText={t('orders.cancelConfirm.okText')} cancelText={t('orders.cancelConfirm.cancelText')}
            onConfirm={() => cancelMutation.mutate(o.id)}
          >
            <Button
              size="small" danger
              loading={cancelMutation.isPending && cancelMutation.variables === o.id}
            >
              {t('orders.table.cancel')}
            </Button>
          </Popconfirm>
        ) : null,
    },
  ]

  return (
    <div>
      <div style={{ marginBottom: 'var(--space-4)' }}>
        <Title level={3} style={{ marginBottom: 4 }}>
          <AimOutlined style={{ color: 'var(--sber-green)', marginRight: 8 }} />
          {t('orders.title')}
        </Title>
        <Text type="secondary">
          {t('orders.subtitle')}
        </Text>
      </div>

      <Card
        className="sber-card"
        style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-light)' }}
      >
        <Segmented
          value={filter}
          onChange={(v) => setFilter(v as Filter)}
          options={[
            { label: t('orders.filters.all'), value: 'ALL' },
            { label: t('orders.filters.open'), value: 'OPEN' },
            { label: t('orders.filters.filled'), value: 'FILLED' },
            { label: t('orders.filters.cancelled'), value: 'CANCELLED' },
          ]}
          style={{ marginBottom: 16 }}
        />

        {!isLoading && (orders ?? []).length === 0 ? (
          <Empty
            description={<Text type="secondary">{t('orders.empty')}</Text>}
            style={{ padding: '32px 0' }}
          >
            <Button type="primary" onClick={() => navigate('/pools')}>{t('orders.emptyCta')}</Button>
          </Empty>
        ) : (
          <Table
            rowKey="id"
            loading={isLoading}
            columns={columns}
            dataSource={filtered}
            pagination={{ pageSize: 12, hideOnSinglePage: true }}
            scroll={{ x: 'max-content' }}
            size="middle"
          />
        )}
      </Card>
    </div>
  )
}
