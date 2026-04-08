import { useState } from 'react'
import { Table, Tag, Typography, Space, Select, DatePicker, Button } from 'antd'
import { FilterOutlined, ReloadOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { transactions } from '@/api/services'
import type { Transaction, TxType, TxStatus, TransactionFilters } from '@/api/types'
import dayjs from 'dayjs'

const { Title, Text } = Typography
const { RangePicker } = DatePicker

const txTypeLabels: Record<string, { text: string; color: string }> = {
  SWAP: { text: 'Обмен', color: 'blue' },
  ADD_LIQUIDITY: { text: 'Добавление ликвидности', color: 'green' },
  REMOVE_LIQUIDITY: { text: 'Удаление ликвидности', color: 'orange' },
  CLAIM_FEE: { text: 'Получение комиссий', color: 'gold' },
  TRANSFER: { text: 'Перевод', color: 'purple' },
  MINT: { text: 'Выпуск', color: 'cyan' },
  BURN: { text: 'Сжигание', color: 'red' },
}

const statusLabels: Record<string, { text: string; color: string }> = {
  PENDING: { text: 'Ожидание', color: 'processing' },
  CONFIRMED: { text: 'Подтверждена', color: 'success' },
  FAILED: { text: 'Ошибка', color: 'error' },
  CANCELLED: { text: 'Отменена', color: 'default' },
}

const txTypeOptions = Object.entries(txTypeLabels).map(([key, val]) => ({
  label: val.text,
  value: key,
}))

const statusOptions = Object.entries(statusLabels).map(([key, val]) => ({
  label: val.text,
  value: key,
}))

export default function TransactionsPage() {
  const [page, setPage] = useState(0)
  const [filters, setFilters] = useState<TransactionFilters>({})
  const pageSize = 20

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['myTransactions', page, filters],
    queryFn: () => transactions.getMyTransactions(page, pageSize, filters),
  })

  const handleDateChange = (_: unknown, dateStrings: [string, string]) => {
    setFilters((prev) => ({
      ...prev,
      dateFrom: dateStrings[0] || undefined,
      dateTo: dateStrings[1] || undefined,
    }))
    setPage(0)
  }

  const handleReset = () => {
    setFilters({})
    setPage(0)
  }

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <Title level={4} className="sber-page-title">История транзакций</Title>

      {/* Filters */}
      <Space wrap>
        <Select
          style={{ width: 200 }}
          placeholder="Тип транзакции"
          allowClear
          options={txTypeOptions}
          value={filters.txType}
          onChange={(v) => { setFilters((prev) => ({ ...prev, txType: v as TxType })); setPage(0) }}
        />
        <Select
          style={{ width: 160 }}
          placeholder="Статус"
          allowClear
          options={statusOptions}
          value={filters.status}
          onChange={(v) => { setFilters((prev) => ({ ...prev, status: v as TxStatus })); setPage(0) }}
        />
        <RangePicker
          onChange={handleDateChange}
          format="YYYY-MM-DD"
          placeholder={['Дата от', 'Дата до']}
        />
        <Button icon={<ReloadOutlined />} onClick={() => refetch()}>Обновить</Button>
        <Button onClick={handleReset}>Сбросить</Button>
      </Space>

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
          showTotal: (total) => `Всего ${total} транзакций`,
        }}
        columns={[
          {
            title: 'Дата',
            dataIndex: 'createdAt',
            width: 160,
            render: (d: string) => dayjs(d).format('DD.MM.YYYY HH:mm'),
          },
          {
            title: 'Тип',
            dataIndex: 'txType',
            width: 200,
            render: (t: string) => {
              const cfg = txTypeLabels[t] || { text: t, color: 'default' }
              return <Tag color={cfg.color}>{cfg.text}</Tag>
            },
          },
          {
            title: 'Сумма входа',
            dataIndex: 'amountIn',
            align: 'right' as const,
            render: (v: number | null) => v != null ? v.toLocaleString('ru-RU') : '—',
          },
          {
            title: 'Сумма выхода',
            dataIndex: 'amountOut',
            align: 'right' as const,
            render: (v: number | null) => v != null ? v.toLocaleString('ru-RU') : '—',
          },
          {
            title: 'Комиссия',
            dataIndex: 'feeAmount',
            align: 'right' as const,
            render: (v: number | null) => v != null ? v.toLocaleString('ru-RU') : '—',
          },
          {
            title: 'Статус',
            dataIndex: 'status',
            width: 140,
            render: (s: string) => {
              const cfg = statusLabels[s] || { text: s, color: 'default' }
              return <Tag color={cfg.color}>{cfg.text}</Tag>
            },
          },
        ]}
      />
    </Space>
  )
}
