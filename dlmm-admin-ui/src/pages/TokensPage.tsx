import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Table,
  Space,
  Tag,
  Typography,
  Card,
  Button,
  Select,
  TablePaginationConfig,
} from 'antd'
import { PlusOutlined, BankOutlined, FilterOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import { tokens as tokenService } from '@/api/services'
import type { Token, TokenType } from '@/api/types'

const { Title, Text } = Typography

// Sprint 9 — full coverage of the backend enum. Was missing the four
// Sprint 6 extensions (FIAT_BACKED / COMMODITY_BACKED / UTILITY /
// INDEX_TOKEN), which meant SUSDT/SCNY/SEUR (FIAT_BACKED) and the
// commodity tokens rendered as raw enum strings next to the properly-
// labelled SBER/GAZP/etc — visibly inconsistent.
const tokenTypeColor: Record<TokenType, string> = {
  STABLE_TOKEN: 'blue',
  EQUITY_TOKEN: 'gold',
  LP_TOKEN: 'cyan',
  GOVERNANCE_TOKEN: 'purple',
  FIAT_BACKED: 'green',
  COMMODITY_BACKED: 'orange',
  UTILITY: 'geekblue',
  INDEX_TOKEN: 'magenta',
}

const tokenTypeLabel: Record<TokenType, string> = {
  STABLE_TOKEN: 'Стейблкоин',
  EQUITY_TOKEN: 'Акция',
  LP_TOKEN: 'LP-токен',
  GOVERNANCE_TOKEN: 'Управление',
  FIAT_BACKED: 'Валюта',
  COMMODITY_BACKED: 'Сырьё',
  UTILITY: 'Утилитарный',
  INDEX_TOKEN: 'Индекс',
}

export default function TokensPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)
  // Sprint 9 — client-side filter by token type. The catalog is small
  // (~20 tokens) so we filter the current page rather than push it to
  // the backend; if the catalogue grows past a couple hundred swap
  // this for a server-side ?type= query.
  const [typeFilter, setTypeFilter] = useState<TokenType | 'ALL'>('ALL')

  const { data, isLoading } = useQuery({
    queryKey: ['tokens', page, pageSize],
    queryFn: () => tokenService.getTokens(page, pageSize),
  })

  const filteredTokens = useMemo(() => {
    const list = data?.content ?? []
    return typeFilter === 'ALL' ? list : list.filter((t) => t.tokenType === typeFilter)
  }, [data, typeFilter])

  const tokenTypeOptions = [
    { value: 'ALL' as const, label: 'Все типы' },
    ...(Object.keys(tokenTypeLabel) as TokenType[]).map((k) => ({
      value: k,
      label: tokenTypeLabel[k],
    })),
  ]

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
      render: (val: number | undefined) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>
          {(val ?? 0).toLocaleString('ru-RU')}
        </span>
      ),
    },
    {
      // Sprint 9 — was "В обращении" tied to circulatingSupply, but the
      // backend doesn't compute that field, so every row rendered "—"
      // and the column was dead weight. Swapped to maxSupply, which IS
      // in the DTO and is the right "ceiling" stat to sit next to total
      // emission. Tokens with no cap (mintable === true and no maxSupply)
      // show "—".
      title: 'Макс. эмиссия',
      dataIndex: 'maxSupply',
      key: 'maxSupply',
      align: 'right',
      render: (val: number | undefined) =>
        typeof val === 'number' && val > 0 ? (
          <span style={{ fontVariantNumeric: 'tabular-nums' }}>
            {val.toLocaleString('ru-RU')}
          </span>
        ) : (
          <Text type="secondary">—</Text>
        ),
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

      <Card
        className="sber-card sber-table"
        style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
        title={
          <Space wrap size={12}>
            <FilterOutlined style={{ color: '#6B7280' }} />
            <Text strong style={{ fontSize: 13 }}>Тип:</Text>
            <Select<TokenType | 'ALL'>
              size="middle"
              style={{ minWidth: 180 }}
              value={typeFilter}
              onChange={(v) => { setTypeFilter(v); setPage(0) }}
              options={tokenTypeOptions}
            />
            <Text type="secondary" style={{ fontSize: 12 }}>
              {filteredTokens.length}{data?.totalElements ? ` из ${data.totalElements}` : ''}
            </Text>
          </Space>
        }
      >
        <Table<Token>
          columns={columns}
          dataSource={filteredTokens}
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
