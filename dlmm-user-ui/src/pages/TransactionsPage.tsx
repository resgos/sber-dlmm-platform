import { useMemo, useState } from 'react'
import { Table, Tag, Typography, Space, Select, DatePicker, Button } from 'antd'
import { DownloadOutlined, FilterOutlined, ReloadOutlined, SwapOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import i18n from '@/i18n'
import { transactions } from '@/api/services'
import type { Transaction, TxType, TxStatus, TransactionFilters } from '@/api/types'
import dayjs from 'dayjs'
import { TokenPairChip } from '@/components/sber'
import EmptyState from '@/components/EmptyState'
import { formatTokenAmount } from '@/lib/format'
import { exportToCsv } from '@/lib/csvExport'

const { Title, Text } = Typography
const { RangePicker } = DatePicker

// Sprint 8 C-4 (rest) — visible labels resolved at render via i18n; only the
// tag colour stays as static config here. CSV export (a downloaded file, not
// the UI) reads the same labels through i18n.t so the file matches the screen.
const txTypeColors: Record<string, string> = {
  SWAP: 'blue',
  ADD_LIQUIDITY: 'green',
  REMOVE_LIQUIDITY: 'orange',
  CLAIM_FEE: 'gold',
  TRANSFER: 'purple',
  MINT: 'cyan',
  BURN: 'red',
}

const statusColors: Record<string, string> = {
  PENDING: 'processing',
  CONFIRMED: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
}

const txTypeLabel = (key: string) => i18n.t(`transactions.txType.${key}`, { defaultValue: key })
const statusLabel = (key: string) => i18n.t(`transactions.txStatus.${key}`, { defaultValue: key })

export default function TransactionsPage() {
  const { t } = useTranslation()
  const [page, setPage] = useState(0)
  const [filters, setFilters] = useState<TransactionFilters>({})
  const pageSize = 20

  const { data, isLoading, refetch } = useQuery({
    queryKey: ['myTransactions', page, filters],
    queryFn: () => transactions.getMyTransactions(page, pageSize, filters),
  })

  // Sprint 9 / Audit B3 — the transaction read API now returns self-describing
  // labels (tokenInSymbol / tokenOutSymbol / poolName), so the columns render
  // straight from the row. No client-side tokens/pools catalogue join needed.

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

  const txTypeOptions = useMemo(
    () => Object.keys(txTypeColors).map((key) => ({ label: txTypeLabel(key), value: key })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [i18n.language],
  )
  const statusOptions = useMemo(
    () => Object.keys(statusColors).map((key) => ({ label: statusLabel(key), value: key })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [i18n.language],
  )

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <Title level={4} className="sber-page-title">{t('transactions.title')}</Title>

      {/* Filters */}
      <Space wrap>
        <Select
          style={{ width: 200 }}
          placeholder={t('transactions.filters.txTypePlaceholder')}
          allowClear
          options={txTypeOptions}
          value={filters.txType}
          onChange={(v) => { setFilters((prev) => ({ ...prev, txType: v as TxType })); setPage(0) }}
        />
        <Select
          style={{ width: 160 }}
          placeholder={t('transactions.filters.statusPlaceholder')}
          allowClear
          options={statusOptions}
          value={filters.status}
          onChange={(v) => { setFilters((prev) => ({ ...prev, status: v as TxStatus })); setPage(0) }}
        />
        <RangePicker
          onChange={handleDateChange}
          format="YYYY-MM-DD"
          placeholder={[t('transactions.filters.dateFrom'), t('transactions.filters.dateTo')]}
        />
        <Button icon={<ReloadOutlined />} onClick={() => refetch()}>{t('transactions.filters.refresh')}</Button>
        <Button onClick={handleReset}>{t('transactions.filters.reset')}</Button>
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
                { header: 'Тип', accessor: (r) => txTypeLabel(r.txType) },
                { header: 'Статус', accessor: (r) => statusLabel(r.status) },
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
        locale={{
          emptyText:
            filters.txType || filters.status || filters.dateFrom || filters.dateTo ? (
              <EmptyState
                size="compact"
                title={t('transactions.empty.filteredTitle')}
                description={t('transactions.empty.filteredDesc')}
                secondary={
                  <Button type="link" onClick={handleReset}>
                    {t('transactions.empty.filteredReset')}
                  </Button>
                }
              />
            ) : (
              <EmptyState
                title={t('transactions.empty.title')}
                description={t('transactions.empty.desc')}
              />
            ),
        }}
        pagination={{
          current: page + 1,
          pageSize,
          total: data?.totalElements || 0,
          onChange: (p) => setPage(p - 1),
          showSizeChanger: false,
          showTotal: (total) => t('transactions.pagination.totalCount', { count: total }),
        }}
        columns={[
          {
            title: t('transactions.table.date'),
            dataIndex: 'createdAt',
            width: 150,
            render: (d: string) => (
              <span style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 'var(--text-sm)' }}>
                {dayjs(d).format('DD.MM.YYYY HH:mm')}
              </span>
            ),
          },
          {
            title: t('transactions.table.type'),
            dataIndex: 'txType',
            width: 180,
            render: (type: string) => {
              const color = txTypeColors[type] || 'default'
              return <Tag color={color}>{txTypeLabel(type)}</Tag>
            },
          },
          {
            // Sprint 9 — was the only thing missing from this table that
            // turned it from "transaction list" into "list of random
            // numbers". Show the pool pair (from poolId) for liquidity
            // ops, or "tokenIn → tokenOut" for swaps + transfers.
            title: t('transactions.table.pair'),
            key: 'pair',
            render: (_: unknown, r: Transaction) => {
              // Audit B3 — backend-resolved, self-describing labels.
              if (r.tokenInSymbol || r.tokenOutSymbol) {
                return <TokenPairChip x={r.tokenInSymbol} y={r.tokenOutSymbol} />
              }
              if (r.poolName) {
                const [px, py] = r.poolName.split('/')
                return <TokenPairChip x={px} y={py} />
              }
              return <Text type="secondary">—</Text>
            },
          },
          {
            // Sprint 9-DS — switched amount columns to formatTokenAmount
            // (compact above 10k). Same data, half the visual noise.
            title: t('transactions.table.amountIn'),
            dataIndex: 'amountIn',
            align: 'right' as const,
            render: (v: number | null, r: Transaction) => (
              <span style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                {formatTokenAmount(v, r.tokenInSymbol)}
              </span>
            ),
          },
          {
            title: t('transactions.table.amountOut'),
            dataIndex: 'amountOut',
            align: 'right' as const,
            render: (v: number | null, r: Transaction) => (
              <span style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                {formatTokenAmount(v, r.tokenOutSymbol)}
              </span>
            ),
          },
          {
            title: t('transactions.table.fee'),
            dataIndex: 'feeAmount',
            align: 'right' as const,
            render: (v: number | null, r: Transaction) => (
              <span style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                {formatTokenAmount(v, r.tokenInSymbol, { maxFractionDigits: 6 })}
              </span>
            ),
          },
          {
            title: t('transactions.table.status'),
            dataIndex: 'status',
            width: 140,
            render: (s: string) => {
              const color = statusColors[s] || 'default'
              return <Tag color={color}>{statusLabel(s)}</Tag>
            },
          },
        ]}
      />
    </Space>
  )
}
