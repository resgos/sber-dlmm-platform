import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Table,
  Space,
  Tag,
  Typography,
  Card,
  Button,
  TablePaginationConfig,
} from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import { pools as poolService } from '@/api/services'
import type { Pool, PoolStatus } from '@/api/types'

const { Title } = Typography

const poolStatusColor: Record<PoolStatus, string> = {
  ACTIVE: 'green',
  PAUSED: 'orange',
  SHUTDOWN: 'red',
  PENDING: 'blue',
}

const poolStatusLabel: Record<PoolStatus, string> = {
  ACTIVE: 'Активен',
  PAUSED: 'Приостановлен',
  SHUTDOWN: 'Остановлен',
  PENDING: 'Ожидание',
}

export default function PoolsPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)

  const { data, isLoading } = useQuery({
    queryKey: ['pools', page, pageSize],
    queryFn: () => poolService.getPools(page, pageSize),
  })

  const columns: ColumnsType<Pool> = [
    {
      title: 'Пара',
      key: 'pair',
      render: (_, record) => (
        <strong style={{ color: '#1F2937' }}>
          {record.tokenXSymbol}/{record.tokenYSymbol}
        </strong>
      ),
    },
    {
      title: 'Шаг бина',
      dataIndex: 'binStep',
      key: 'binStep',
      align: 'right',
      render: (val: number) => `${val} bps`,
    },
    {
      title: 'Базовая комиссия',
      dataIndex: 'baseFeeBps',
      key: 'baseFeeBps',
      align: 'right',
      render: (val: number) => `${val} bps`,
    },
    {
      title: 'Текущая цена',
      dataIndex: 'currentPrice',
      key: 'currentPrice',
      align: 'right',
      render: (val: number) => val.toLocaleString('ru-RU', { maximumFractionDigits: 6 }),
    },
    {
      title: 'TVL (X)',
      dataIndex: 'totalTvlX',
      key: 'totalTvlX',
      align: 'right',
      render: (val: number) => val.toLocaleString('ru-RU', { maximumFractionDigits: 2 }),
    },
    {
      title: 'TVL (Y)',
      dataIndex: 'totalTvlY',
      key: 'totalTvlY',
      align: 'right',
      render: (val: number) => val.toLocaleString('ru-RU', { maximumFractionDigits: 2 }),
    },
    {
      title: 'Объём 24ч',
      dataIndex: 'volume24h',
      key: 'volume24h',
      align: 'right',
      render: (val: number) => val.toLocaleString('ru-RU', { maximumFractionDigits: 2 }),
    },
    {
      title: 'Расч. APY',
      dataIndex: 'estimatedApy',
      key: 'estimatedApy',
      align: 'right',
      render: (val: number) => (
        <span style={{ color: val > 0 ? '#21A038' : undefined, fontWeight: 500 }}>
          {val.toFixed(2)}%
        </span>
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      render: (status: PoolStatus) => (
        <Tag color={poolStatusColor[status]}>{poolStatusLabel[status] || status}</Tag>
      ),
    },
  ]

  const handleTableChange = (pagination: TablePaginationConfig) => {
    setPage((pagination.current ?? 1) - 1)
    setPageSize(pagination.pageSize ?? 20)
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} className="sber-page-title">
          Пулы ликвидности
        </Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => navigate('/pools/create')}
          style={{ borderRadius: 8 }}
        >
          Создать пул
        </Button>
      </div>

      <Card className="sber-card sber-table" style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}>
        <Table<Pool>
          columns={columns}
          dataSource={data?.content}
          rowKey="id"
          loading={isLoading}
          pagination={{
            current: page + 1,
            pageSize,
            total: data?.totalElements,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (total) => `Всего ${total} пулов`,
          }}
          onChange={handleTableChange}
          onRow={(record) => ({
            onClick: () => navigate(`/pools/${record.id}`),
            style: { cursor: 'pointer' },
          })}
          size="middle"
          scroll={{ x: 1000 }}
        />
      </Card>
    </Space>
  )
}
