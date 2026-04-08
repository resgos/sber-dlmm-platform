import { useState } from 'react'
import { Table, Tag, Typography, Space, Button } from 'antd'
import { FundOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { pools } from '@/api/services'
import type { Pool } from '@/api/types'
import { formatRub } from '@/components/StatCard'

const { Title, Text } = Typography

const statusColors: Record<string, string> = {
  ACTIVE: 'success',
  PAUSED: 'warning',
  SHUTDOWN: 'error',
  PENDING: 'processing',
}

export default function PoolsPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const pageSize = 20

  const { data, isLoading } = useQuery({
    queryKey: ['pools', page],
    queryFn: () => pools.getPools(page, pageSize),
  })

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} className="sber-page-title">Пулы ликвидности</Title>
      </div>

      <Table
        className="sber-table"
        loading={isLoading}
        dataSource={data?.content || []}
        rowKey="id"
        pagination={{
          current: page + 1,
          pageSize,
          total: data?.totalElements || 0,
          onChange: (p) => setPage(p - 1),
          showSizeChanger: false,
        }}
        onRow={(record) => ({
          style: { cursor: 'pointer' },
          onClick: () => navigate(`/pools/${record.id}`),
        })}
        columns={[
          {
            title: 'Пара',
            key: 'pair',
            render: (_: unknown, r: Pool) => (
              <Space>
                <div style={{
                  width: 36, height: 36, borderRadius: 18, background: '#E8F5E9',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontWeight: 700, fontSize: 11, color: '#21A038',
                }}>
                  <FundOutlined />
                </div>
                <Text strong>{r.tokenXSymbol}/{r.tokenYSymbol}</Text>
              </Space>
            ),
          },
          {
            title: 'Цена',
            dataIndex: 'currentPrice',
            align: 'right' as const,
            render: (v: number) => v?.toLocaleString('ru-RU', { maximumFractionDigits: 4 }) ?? '—',
          },
          {
            title: 'TVL',
            key: 'tvl',
            align: 'right' as const,
            render: (_: unknown, r: Pool) => formatRub(r.totalTvlX + r.totalTvlY),
          },
          {
            title: 'Объём 24ч',
            dataIndex: 'volume24h',
            align: 'right' as const,
            render: (v: number) => formatRub(v),
          },
          {
            title: 'APY',
            dataIndex: 'estimatedApy',
            align: 'right' as const,
            render: (v: number) => (
              <Text strong style={{ color: '#21A038' }}>{v?.toFixed(1)}%</Text>
            ),
          },
          {
            title: 'Комиссия',
            dataIndex: 'baseFeeBps',
            align: 'right' as const,
            render: (v: number) => `${v} bps`,
          },
          {
            title: 'Статус',
            dataIndex: 'status',
            render: (s: string) => <Tag color={statusColors[s] || 'default'}>{s}</Tag>,
          },
          {
            title: '',
            key: 'action',
            render: (_: unknown, r: Pool) => (
              <Button type="link" size="small" onClick={(e) => { e.stopPropagation(); navigate(`/pools/${r.id}/liquidity`) }}>
                Добавить ликвидность
              </Button>
            ),
          },
        ]}
      />
    </Space>
  )
}
