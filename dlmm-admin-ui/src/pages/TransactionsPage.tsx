import { useMemo, useState } from 'react'
import {
  Table,
  Space,
  Tag,
  Typography,
  Card,
  Button,
  Select,
  DatePicker,
  TablePaginationConfig,
  Tooltip,
} from 'antd'
import { DownloadOutlined, FilterOutlined, SwapOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import type { RangePickerProps } from 'antd/es/date-picker'
import { transactions as txService, tokens as tokensApi, pools as poolsApi } from '@/api/services'
import type {
  Transaction,
  TxType,
  TxStatus,
  TransactionFilters,
  Token,
  Pool,
} from '@/api/types'
import dayjs from 'dayjs'

const { Title, Text } = Typography
const { RangePicker } = DatePicker

const txStatusColor: Record<TxStatus, string> = {
  PENDING: 'orange',
  CONFIRMED: 'green',
  FAILED: 'red',
  CANCELLED: 'default',
}

const txTypeColor: Record<TxType, string> = {
  SWAP: 'blue',
  ADD_LIQUIDITY: 'green',
  REMOVE_LIQUIDITY: 'orange',
  MINT: 'cyan',
  BURN: 'red',
  TRANSFER: 'purple',
}

const txTypeLabel: Record<TxType, string> = {
  SWAP: 'Обмен',
  ADD_LIQUIDITY: 'Добавить ликвидность',
  REMOVE_LIQUIDITY: 'Снять ликвидность',
  MINT: 'Выпуск',
  BURN: 'Сжигание',
  TRANSFER: 'Перевод',
}

const txStatusLabel: Record<TxStatus, string> = {
  PENDING: 'Ожидание',
  CONFIRMED: 'Подтверждена',
  FAILED: 'Ошибка',
  CANCELLED: 'Отменена',
}

const txTypeOptions: { value: TxType; label: string }[] = [
  { value: 'SWAP', label: 'Обмен' },
  { value: 'ADD_LIQUIDITY', label: 'Добавить ликвидность' },
  { value: 'REMOVE_LIQUIDITY', label: 'Снять ликвидность' },
  { value: 'MINT', label: 'Выпуск' },
  { value: 'BURN', label: 'Сжигание' },
  { value: 'TRANSFER', label: 'Перевод' },
]

const txStatusOptions: { value: TxStatus; label: string }[] = [
  { value: 'PENDING', label: 'Ожидание' },
  { value: 'CONFIRMED', label: 'Подтверждена' },
  { value: 'FAILED', label: 'Ошибка' },
  { value: 'CANCELLED', label: 'Отменена' },
]

function exportCsv(data: Transaction[]) {
  const headers = [
    'ID',
    'Тип',
    'Статус',
    'ID пользователя',
    'ID пула',
    'Сумма входа',
    'Сумма выхода',
    'Комиссия',
    'Ставка комиссии',
    'Пересечено бинов',
    'Дата создания',
    'Ошибка',
  ]
  const rows = data.map((tx) => [
    tx.id,
    tx.txType,
    tx.status,
    tx.userId,
    tx.poolId ?? '',
    tx.amountIn ?? '',
    tx.amountOut ?? '',
    tx.feeAmount ?? '',
    tx.feeRate ?? '',
    tx.binsCrossed ?? '',
    tx.createdAt,
    tx.errorMessage ?? '',
  ])

  const csvContent = [headers, ...rows]
    .map((row) => row.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\n')

  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `транзакции_${dayjs().format('YYYY-MM-DD_HH-mm-ss')}.csv`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

export default function TransactionsPage() {
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)
  const [filters, setFilters] = useState<TransactionFilters>({})
  const [dateRange, setDateRange] = useState<[string, string] | null>(null)

  const appliedFilters: TransactionFilters = {
    ...filters,
    ...(dateRange ? { dateFrom: dateRange[0], dateTo: dateRange[1] } : {}),
  }

  const { data, isLoading } = useQuery({
    queryKey: ['transactions', page, pageSize, appliedFilters],
    queryFn: () => txService.getTransactions(page, pageSize, appliedFilters),
  })

  // Sprint 9 — same client-side join applied on user-ui TransactionsPage:
  // Transaction DTOs carry tokenInId / tokenOutId / poolId but no symbols.
  // Without these, an admin looking at the global ledger sees "104 037 396"
  // and has no idea which token moved. Pull the catalogues once, join here.
  const { data: tokenPage } = useQuery({
    queryKey: ['tokens'],
    queryFn: () => tokensApi.getTokens(0, 200),
  })
  const { data: poolPage } = useQuery({
    queryKey: ['admin-pools', 0, 100],
    queryFn: () => poolsApi.getPools(0, 100),
  })
  const symbolByTokenId = useMemo(() => {
    const m = new Map<string, string>()
    for (const t of tokenPage?.content ?? []) m.set(t.id, t.symbol)
    return m
  }, [tokenPage])
  const pairByPoolId = useMemo(() => {
    const m = new Map<string, string>()
    for (const p of poolPage?.content ?? []) m.set(p.id, `${p.tokenXSymbol}/${p.tokenYSymbol}`)
    return m
  }, [poolPage])

  // Sprint 9 — render the last 6 chars of an ID instead of the first 8.
  // The seed leaves identical prefixes ("88000000…" / "a0000000…") which
  // made every row look identical at a glance; the tail is unique.
  const renderShortId = (id: string) => (
    <Tooltip title={id}>
      <span style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12, color: 'var(--text-secondary)' }}>
        …{id.slice(-6)}
      </span>
    </Tooltip>
  )

  const columns: ColumnsType<Transaction> = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 90,
      render: renderShortId,
    },
    {
      title: 'Тип',
      dataIndex: 'txType',
      key: 'txType',
      width: 160,
      render: (type: TxType) => (
        <Tag color={txTypeColor[type] || 'default'}>{txTypeLabel[type] || type}</Tag>
      ),
    },
    {
      // Sprint 9 — was the missing piece that made this table read as
      // "list of random numbers". Same join as user-ui TransactionsPage:
      // tokenIn/Out → symbols for swaps, pool.pair for liquidity ops.
      title: 'Пара / направление',
      key: 'pair',
      render: (_: unknown, r: Transaction) => {
        const inSym = r.tokenInId ? symbolByTokenId.get(r.tokenInId) : null
        const outSym = r.tokenOutId ? symbolByTokenId.get(r.tokenOutId) : null
        if (inSym && outSym) {
          return (
            <Space size={6}>
              <Text strong style={{ fontSize: 13 }}>{inSym}</Text>
              <SwapOutlined style={{ color: 'var(--text-muted, #9CA3AF)', fontSize: 11 }} />
              <Text strong style={{ fontSize: 13 }}>{outSym}</Text>
            </Space>
          )
        }
        if (r.poolId) {
          const pair = pairByPoolId.get(r.poolId)
          if (pair) return <Text strong style={{ fontSize: 13 }}>{pair}</Text>
        }
        return <Text type="secondary">—</Text>
      },
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      width: 130,
      render: (status: TxStatus) => (
        <Tag color={txStatusColor[status]}>{txStatusLabel[status] || status}</Tag>
      ),
    },
    {
      title: 'Пользователь',
      dataIndex: 'userId',
      key: 'userId',
      width: 110,
      render: renderShortId,
    },
    {
      title: 'Сумма входа',
      dataIndex: 'amountIn',
      key: 'amountIn',
      align: 'right',
      render: (val: number | null, r: Transaction) => {
        if (val === null) return <Text type="secondary">—</Text>
        const sym = r.tokenInId ? symbolByTokenId.get(r.tokenInId) : null
        return (
          <span style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
            {val.toLocaleString('ru-RU', { maximumFractionDigits: 4 })}
            {sym && (
              <Text type="secondary" style={{ fontSize: 11, marginLeft: 6 }}>{sym}</Text>
            )}
          </span>
        )
      },
    },
    {
      title: 'Сумма выхода',
      dataIndex: 'amountOut',
      key: 'amountOut',
      align: 'right',
      render: (val: number | null, r: Transaction) => {
        if (val === null) return <Text type="secondary">—</Text>
        const sym = r.tokenOutId ? symbolByTokenId.get(r.tokenOutId) : null
        return (
          <span style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
            {val.toLocaleString('ru-RU', { maximumFractionDigits: 4 })}
            {sym && (
              <Text type="secondary" style={{ fontSize: 11, marginLeft: 6 }}>{sym}</Text>
            )}
          </span>
        )
      },
    },
    {
      title: 'Комиссия',
      dataIndex: 'feeAmount',
      key: 'feeAmount',
      align: 'right',
      render: (val: number | null, r: Transaction) => {
        if (val === null) return <Text type="secondary">—</Text>
        const sym = r.tokenInId ? symbolByTokenId.get(r.tokenInId) : null
        return (
          <span style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
            {val.toLocaleString('ru-RU', { maximumFractionDigits: 6 })}
            {sym && (
              <Text type="secondary" style={{ fontSize: 11, marginLeft: 6 }}>{sym}</Text>
            )}
          </span>
        )
      },
    },
    {
      title: 'Дата создания',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 140,
      render: (date: string) => (
        <span style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12 }}>
          {dayjs(date).format('DD.MM.YYYY HH:mm')}
        </span>
      ),
      sorter: (a, b) => dayjs(a.createdAt).unix() - dayjs(b.createdAt).unix(),
    },
  ]

  const handleTableChange = (pagination: TablePaginationConfig) => {
    setPage((pagination.current ?? 1) - 1)
    setPageSize(pagination.pageSize ?? 20)
  }

  const handleDateChange: RangePickerProps['onChange'] = (dates, dateStrings) => {
    if (dates && dates[0] && dates[1]) {
      setDateRange([dateStrings[0], dateStrings[1]])
    } else {
      setDateRange(null)
    }
    setPage(0)
  }

  const handleExport = () => {
    if (data?.content) {
      exportCsv(data.content)
    }
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Title level={4} className="sber-page-title">
          Транзакции
        </Title>
        <Button
          icon={<DownloadOutlined />}
          onClick={handleExport}
          disabled={!data?.content?.length}
          style={{ borderRadius: 8 }}
        >
          Экспорт CSV
        </Button>
      </div>

      <Card
        className="sber-card sber-table"
        style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
        title={
          <Space wrap>
            <FilterOutlined style={{ color: '#6B7280' }} />
            <Select<TxType>
              placeholder="Фильтр по типу"
              allowClear
              style={{ width: 180 }}
              options={txTypeOptions}
              onChange={(val) => {
                setFilters((f) => ({ ...f, txType: val }))
                setPage(0)
              }}
            />
            <Select<TxStatus>
              placeholder="Фильтр по статусу"
              allowClear
              style={{ width: 160 }}
              options={txStatusOptions}
              onChange={(val) => {
                setFilters((f) => ({ ...f, status: val }))
                setPage(0)
              }}
            />
            <RangePicker
              onChange={handleDateChange}
              format="YYYY-MM-DD"
              placeholder={['Начальная дата', 'Конечная дата']}
            />
          </Space>
        }
      >
        <Table<Transaction>
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
            showTotal: (total) => `Всего ${total} транзакций`,
            pageSizeOptions: ['10', '20', '50', '100'],
          }}
          onChange={handleTableChange}
          size="middle"
          scroll={{ x: 900 }}
        />
      </Card>
    </Space>
  )
}
