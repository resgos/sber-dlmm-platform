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
import {
  PlusOutlined,
  BankOutlined,
  FilterOutlined,
  AppstoreOutlined,
  GoldOutlined,
  DollarOutlined,
  CheckCircleOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import { tokens as tokenService } from '@/api/services'
import type { Token, TokenType } from '@/api/types'
import { KpiRow, PageHeader } from '@/components/sber'
import { formatCompact } from '@/lib/format'

const { Text } = Typography

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
          {formatCompact(val ?? 0)}
        </span>
      ),
    },
    {
      title: 'Макс. эмиссия',
      dataIndex: 'maxSupply',
      key: 'maxSupply',
      align: 'right',
      render: (val: number | undefined) =>
        typeof val === 'number' && val > 0 ? (
          <span style={{ fontVariantNumeric: 'tabular-nums' }}>
            {formatCompact(val)}
          </span>
        ) : (
          <Text type="secondary">∞</Text>
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

  // Sprint 9-DS — KPI tile aggregates over current page slice.
  const all = data?.content ?? []
  const activeCount = all.filter((t) => t.active).length
  const fiatBackedCount = all.filter((t) => t.tokenType === 'FIAT_BACKED').length
  const equityCount = all.filter((t) => t.tokenType === 'EQUITY_TOKEN').length
  const commodityCount = all.filter((t) => t.tokenType === 'COMMODITY_BACKED').length

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="Токены"
        subtitle="Каталог активов платформы — стейблкоины, акции, сырьё, индексы"
        actions={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/tokens/create')}
            style={{ borderRadius: 8 }}
          >
            Создать токен
          </Button>
        }
      />

      <KpiRow
        tiles={[
          {
            label: 'Всего токенов',
            value: (data?.totalElements ?? all.length).toLocaleString('ru-RU'),
            sub: `${activeCount} активн${activeCount === 1 ? 'ый' : activeCount < 5 ? 'ых' : 'ых'}`,
            icon: <AppstoreOutlined style={{ color: 'var(--sber-green)' }} />,
          },
          {
            label: 'Валютные',
            value: fiatBackedCount.toLocaleString('ru-RU'),
            sub: 'FIAT_BACKED',
            icon: <DollarOutlined style={{ color: '#296AE3' }} />,
          },
          {
            label: 'Акции',
            value: equityCount.toLocaleString('ru-RU'),
            sub: 'торгуемые на MOEX',
            icon: <BankOutlined style={{ color: '#9B59B6' }} />,
          },
          {
            label: 'Сырьё / индексы',
            value: (commodityCount + all.filter((t) => t.tokenType === 'INDEX_TOKEN').length)
              .toLocaleString('ru-RU'),
            sub: 'commodity + indexes',
            icon: <GoldOutlined style={{ color: '#F2994A' }} />,
          },
        ]}
      />

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
