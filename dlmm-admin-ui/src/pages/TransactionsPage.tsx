import { useState } from 'react'
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
import { DownloadOutlined, FilterOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import type { RangePickerProps } from 'antd/es/date-picker'
import { transactions as txService } from '@/api/services'
import type {
  Transaction,
  TxType,
  TxStatus,
  TransactionFilters,
} from '@/api/types'
import dayjs from 'dayjs'

const { Title } = Typography
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

  const columns: ColumnsType<Transaction> = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 120,
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
      dataIndex: 'txType',
      key: 'txType',
      render: (type: TxType) => (
        <Tag color={txTypeColor[type] || 'default'}>{txTypeLabel[type] || type}</Tag>
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      render: (status: TxStatus) => (
        <Tag color={txStatusColor[status]}>{txStatusLabel[status] || status}</Tag>
      ),
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
      title: 'Сумма входа',
      dataIndex: 'amountIn',
      key: 'amountIn',
      align: 'right',
      render: (val: number | null) =>
        val !== null ? val.toLocaleString('ru-RU', { maximumFractionDigits: 4 }) : '—',
    },
    {
      title: 'Сумма выхода',
      dataIndex: 'amountOut',
      key: 'amountOut',
      align: 'right',
      render: (val: number | null) =>
        val !== null ? val.toLocaleString('ru-RU', { maximumFractionDigits: 4 }) : '—',
    },
    {
      title: 'Комиссия',
      dataIndex: 'feeAmount',
      key: 'feeAmount',
      align: 'right',
      render: (val: number | null) =>
        val !== null ? val.toLocaleString('ru-RU', { maximumFractionDigits: 6 }) : '—',
    },
    {
      title: 'Дата создания',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (date: string) => dayjs(date).format('YYYY-MM-DD HH:mm'),
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
