import { useMemo, useState } from 'react'
import { Table, Tag, Typography, Space, Select, DatePicker, Button } from 'antd'
import { DownloadOutlined, FilterOutlined, ReloadOutlined, SwapOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { transactions, tokens as tokensApi, pools as poolsApi } from '@/api/services'
import type { Transaction, TxType, TxStatus, TransactionFilters, Token, Pool } from '@/api/types'
import dayjs from 'dayjs'
import { TokenPairChip } from '@/components/sber'
import { formatTokenAmount } from '@/lib/format'
import { exportToCsv } from '@/lib/csvExport'

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

  // Sprint 9 — Transaction DTO carries tokenInId / tokenOutId / poolId but
  // no symbols, so a swap was rendering as "Обмен / 104 037 396 / 107 755 701"
  // with no hint of which pair was traded. Pull the (cached) tokens and
  // pools catalogues and join client-side.
  const { data: tokenPage } = useQuery({
    queryKey: ['tokens'],
    queryFn: () => tokensApi.getTokens(0, 200),
  })
  const { data: poolPage } = useQuery({
    queryKey: ['pools', 0, 100],
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
        {/* QW-1 (Batch #4) — CSV export of the currently-filtered page.
            Exports the page the user is looking at, not all data — keeps
            the helper sync, and matches user expectation ("download what
            I see"). For bulk exports we'd add a server-side endpoint. */}
        <Button
          icon={<DownloadOutlined />}
          onClick={() => {
            const rows = data?.content || []
            if (rows.length === 0) return
            exportToCsv(
              `dlmm-transactions-${dayjs().format('YYYY-MM-DD')}.csv`,
              rows,
              [
                { header: 'Дата', accessor: (r: Transaction) => dayjs(r.createdAt).format('YYYY-MM-DD HH:mm:ss') },
                { header: 'Тип', accessor: (r) => txTypeLabels[r.txType]?.text ?? r.txType },
                { header: 'Статус', accessor: (r) => statusLabels[r.status]?.text ?? r.status },
                { header: 'Пул ID', accessor: (r) => r.poolId ?? '' },
                { header: 'Token In ID', accessor: (r) => r.tokenInId ?? '' },
                { header: 'Token Out ID', accessor: (r) => r.tokenOutId ?? '' },
                { header: 'Amount In', accessor: (r) => r.amountIn ?? '' },
                { header: 'Amount Out', accessor: (r) => r.amountOut ?? '' },
                { header: 'Fee', accessor: (r) => r.feeAmount ?? '' },
                { header: 'Bins Crossed', accessor: (r) => r.binsCrossed ?? '' },
                { header: 'Error', accessor: (r) => r.errorMessage ?? '' },
              ],
            )
          }}
          disabled={!data?.content || data.content.length === 0}
        >
          CSV
        </Button>
      </Space>

      <Table
        scroll={{ x: 'max-content' }}
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
            width: 150,
            render: (d: string) => (
              <span style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 'var(--text-sm)' }}>
                {dayjs(d).format('DD.MM.YYYY HH:mm')}
              </span>
            ),
          },
          {
            title: 'Тип',
            dataIndex: 'txType',
            width: 180,
            render: (t: string) => {
              const cfg = txTypeLabels[t] || { text: t, color: 'default' }
              return <Tag color={cfg.color}>{cfg.text}</Tag>
            },
          },
          {
            // Sprint 9 — was the only thing missing from this table that
            // turned it from "transaction list" into "list of random
            // numbers". Show the pool pair (from poolId) for liquidity
            // ops, or "tokenIn → tokenOut" for swaps + transfers.
            title: 'Пара / направление',
            key: 'pair',
            render: (_: unknown, r: Transaction) => {
              // Audit B3 — prefer backend-resolved labels (self-describing API);
              // fall back to the client token/pool catalogue join.
              const inSym = r.tokenInSymbol ?? (r.tokenInId ? symbolByTokenId.get(r.tokenInId) : null)
              const outSym = r.tokenOutSymbol ?? (r.tokenOutId ? symbolByTokenId.get(r.tokenOutId) : null)
              if (inSym || outSym) return <TokenPairChip x={inSym} y={outSym} />
              if (r.poolId) {
                const pair = r.poolName ?? pairByPoolId.get(r.poolId)
                if (pair) {
                  const [px, py] = pair.split('/')
                  return <TokenPairChip x={px} y={py} />
                }
              }
              return <Text type="secondary">—</Text>
            },
          },
          {
            // Sprint 9-DS — switched amount columns to formatTokenAmount
            // (compact above 10k). Same data, half the visual noise.
            title: 'Сумма входа',
            dataIndex: 'amountIn',
            align: 'right' as const,
            render: (v: number | null, r: Transaction) => (
              <span style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                {formatTokenAmount(v, r.tokenInId ? symbolByTokenId.get(r.tokenInId) : undefined)}
              </span>
            ),
          },
          {
            title: 'Сумма выхода',
            dataIndex: 'amountOut',
            align: 'right' as const,
            render: (v: number | null, r: Transaction) => (
              <span style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                {formatTokenAmount(v, r.tokenOutId ? symbolByTokenId.get(r.tokenOutId) : undefined)}
              </span>
            ),
          },
          {
            title: 'Комиссия',
            dataIndex: 'feeAmount',
            align: 'right' as const,
            render: (v: number | null, r: Transaction) => (
              <span style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                {formatTokenAmount(v, r.tokenInId ? symbolByTokenId.get(r.tokenInId) : undefined, { maxFractionDigits: 6 })}
              </span>
            ),
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
