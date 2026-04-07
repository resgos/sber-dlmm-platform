import {
  Table,
  Space,
  Tag,
  Typography,
  Card,
  Alert,
  Spin,
  Tooltip,
} from 'antd'
import { WarningOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import { transactions as txService } from '@/api/services'
import type { SuspiciousTransaction } from '@/api/types'
import dayjs from 'dayjs'

const { Title } = Typography

export default function SuspiciousTransactionsPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['suspiciousTransactions'],
    queryFn: txService.getSuspiciousTransactions,
    refetchInterval: 30000,
  })

  const columns: ColumnsType<SuspiciousTransaction> = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 130,
      render: (id: string) => (
        <Tooltip title={id}>
          <span style={{ fontFamily: 'monospace', fontSize: 12 }}>
            {id.slice(0, 8)}...
          </span>
        </Tooltip>
      ),
    },
    {
      title: 'Тип',
      dataIndex: 'type',
      key: 'type',
      render: (type: string) => <Tag color="blue">{type}</Tag>,
    },
    {
      title: 'ID пользователя',
      dataIndex: 'userId',
      key: 'userId',
      render: (id: string) => (
        <Tooltip title={id}>
          <span style={{ fontFamily: 'monospace', fontSize: 12 }}>
            {id.slice(0, 8)}...
          </span>
        </Tooltip>
      ),
    },
    {
      title: 'ID пула',
      dataIndex: 'poolId',
      key: 'poolId',
      render: (id: string | null) =>
        id ? (
          <Tooltip title={id}>
            <span style={{ fontFamily: 'monospace', fontSize: 12 }}>
              {id.slice(0, 8)}...
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
        <span style={{ fontWeight: 600 }}>
          {val.toLocaleString('ru-RU', { maximumFractionDigits: 4 })}
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
            {reason.length > 40 ? `${reason.slice(0, 40)}...` : reason}
          </Tag>
        </Tooltip>
      ),
    },
    {
      title: 'Время',
      dataIndex: 'timestamp',
      key: 'timestamp',
      render: (date: string) => dayjs(date).format('YYYY-MM-DD HH:mm:ss'),
      sorter: (a, b) => dayjs(a.timestamp).unix() - dayjs(b.timestamp).unix(),
      defaultSortOrder: 'descend',
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
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} className="sber-page-title">
          <WarningOutlined style={{ color: '#EF4444', marginRight: 8 }} />
          Подозрительные транзакции
        </Title>
        {data && data.length > 0 && (
          <Tag color="red" style={{ fontSize: 13, padding: '4px 12px', borderRadius: 12 }}>
            {data.length} выявлено
          </Tag>
        )}
      </div>

      {data?.length === 0 && (
        <Alert
          message="Подозрительных транзакций не обнаружено"
          description="На данный момент система не выявила подозрительных транзакций."
          type="success"
          showIcon
          style={{ borderRadius: 8 }}
        />
      )}

      <Card
        className="sber-card sber-table"
        style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
        extra={
          <span style={{ color: '#9CA3AF', fontSize: 12 }}>
            Автообновление каждые 30 секунд
          </span>
        }
      >
        <Table<SuspiciousTransaction>
          columns={columns}
          dataSource={data}
          rowKey="id"
          loading={isLoading}
          pagination={{
            pageSize: 20,
            showSizeChanger: true,
            showTotal: (total) => `Всего ${total} подозрительных транзакций`,
          }}
          rowClassName={(record) =>
            record.amount > 1000000 ? 'ant-table-row-danger' : ''
          }
          size="middle"
          scroll={{ x: 800 }}
        />
      </Card>
    </Space>
  )
}
