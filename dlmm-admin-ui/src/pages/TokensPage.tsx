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
import { PlusOutlined, BankOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import { tokens as tokenService } from '@/api/services'
import type { Token, TokenType } from '@/api/types'

const { Title } = Typography

const tokenTypeColor: Record<TokenType, string> = {
  STABLE_TOKEN: 'blue',
  EQUITY_TOKEN: 'gold',
  LP_TOKEN: 'cyan',
  GOVERNANCE_TOKEN: 'purple',
}

const tokenTypeLabel: Record<TokenType, string> = {
  STABLE_TOKEN: 'Стейблкоин',
  EQUITY_TOKEN: 'Товарный',
  LP_TOKEN: 'LP-токен',
  GOVERNANCE_TOKEN: 'Управление',
}

export default function TokensPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)

  const { data, isLoading } = useQuery({
    queryKey: ['tokens', page, pageSize],
    queryFn: () => tokenService.getTokens(page, pageSize),
  })

  const columns: ColumnsType<Token> = [
    {
      title: 'Символ',
      dataIndex: 'symbol',
      key: 'symbol',
      render: (symbol: string) => (
        <Space>
          <BankOutlined style={{ color: '#21A038' }} />
          <strong>{symbol}</strong>
        </Space>
      ),
    },
    {
      title: 'Название',
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: 'Тип',
      dataIndex: 'tokenType',
      key: 'tokenType',
      render: (type: TokenType) => (
        <Tag color={tokenTypeColor[type] || 'default'}>{tokenTypeLabel[type] || type}</Tag>
      ),
    },
    {
      title: 'Десятичные',
      dataIndex: 'decimals',
      key: 'decimals',
      align: 'right',
    },
    {
      title: 'Общая эмиссия',
      dataIndex: 'totalSupply',
      key: 'totalSupply',
      align: 'right',
      render: (val: number) => val.toLocaleString('ru-RU'),
    },
    {
      title: 'В обращении',
      dataIndex: 'circulatingSupply',
      key: 'circulatingSupply',
      align: 'right',
      render: (val: number) => val.toLocaleString('ru-RU'),
    },
    {
      title: 'Статус',
      dataIndex: 'active',
      key: 'active',
      render: (active: boolean) => (
        <Tag color={active ? 'green' : 'red'}>{active ? 'Активен' : 'Неактивен'}</Tag>
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
          Токены
        </Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => navigate('/tokens/create')}
          style={{ borderRadius: 8 }}
        >
          Создать токен
        </Button>
      </div>

      <Card className="sber-card sber-table" style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}>
        <Table<Token>
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
            showTotal: (total) => `Всего ${total} токенов`,
            pageSizeOptions: ['10', '20', '50', '100'],
          }}
          onChange={handleTableChange}
          onRow={(record) => ({
            onClick: () => navigate(`/tokens/${record.id}`),
            style: { cursor: 'pointer' },
          })}
          size="middle"
        />
      </Card>
    </Space>
  )
}
