import { useMemo, useState } from 'react'
import { Table, Tag, Typography, Space, Select, DatePicker, Button } from 'antd'
import { DownloadOutlined, FilterOutlined, ReloadOutlined, SwapOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import i18n from '@/i18n'
import { transactions, pools } from '@/api/services'
import type { Transaction, TxType, TxStatus, TransactionFilters, Pool } from '@/api/types'
import dayjs from 'dayjs'
import { TokenPairChip } from '@/components/sber'
import EmptyState from '@/components/EmptyState'
import { formatTokenAmount } from '@/lib/format'
import { exportToCsv, type CsvColumn } from '@/lib/csvExport'

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

  // Pools populate the "filter by pool" select (label = pair, value = id).
  const { data: poolList } = useQuery({
    queryKey: ['pools'],
    queryFn: () => pools.getPools(0, 100),
  })
  const poolOptions = useMemo(
    () => (poolList?.content ?? []).map((p: Pool) => ({
      label: `${p.tokenXSymbol}/${p.tokenYSymbol}`,
      value: p.id,
    })),
    [poolList],
  )

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

  // QW-1+ (Batch #5) — CSV export of the FULL filtered result set (not just the
  // 20-row page on screen) with human-readable symbols/pool, matching what the
  // user sees rather than raw UUIDs. Loops the paged API at the service-wide
  // 200/page ceiling (no skip: offset = page×200), bounded by EXPORT_HARD_CAP so
  // a pathological history can't hang the tab. amountIn/Out/fee are already
  // human-scaled by scaleTransaction. Beyond the cap we'd stream server-side.
  const EXPORT_PAGE_SIZE = 200
  const EXPORT_HARD_CAP = 5000
  const [exporting, setExporting] = useState(false)

  const handleExportCsv = async () => {
    setExporting(true)
    try {
      const all: Transaction[] = []
      let p = 0
      for (;;) {
        const res = await transactions.getMyTransactions(p, EXPORT_PAGE_SIZE, filters)
        const chunk = res.content ?? []
        all.push(...chunk)
        const total = res.totalElements ?? all.length
        if (chunk.length === 0 || all.length >= total || all.length >= EXPORT_HARD_CAP) break
        p++
      }
      if (all.length === 0) return
      const columns: CsvColumn<Transaction>[] = [
        { header: t('transactions.csv.id'), accessor: (r) => r.id },
        { header: t('transactions.csv.date'), accessor: (r) => dayjs(r.createdAt).format('YYYY-MM-DD HH:mm:ss') },
        { header: t('transactions.csv.type'), accessor: (r) => txTypeLabel(r.txType) },
        { header: t('transactions.csv.status'), accessor: (r) => statusLabel(r.status) },
        { header: t('transactions.csv.pool'), accessor: (r) => r.poolName ?? '' },
        { header: t('transactions.csv.tokenIn'), accessor: (r) => r.tokenInSymbol ?? '' },
        { header: t('transactions.csv.amountIn'), accessor: (r) => r.amountIn ?? '' },
        { header: t('transactions.csv.tokenOut'), accessor: (r) => r.tokenOutSymbol ?? '' },
        { header: t('transactions.csv.amountOut'), accessor: (r) => r.amountOut ?? '' },
        { header: t('transactions.csv.fee'), accessor: (r) => r.feeAmount ?? '' },
        { header: t('transactions.csv.binsCrossed'), accessor: (r) => r.binsCrossed ?? '' },
        { header: t('transactions.csv.error'), accessor: (r) => r.errorMessage ?? '' },
      ]
      exportToCsv(`dlmm-transactions-${dayjs().format('YYYY-MM-DD')}.csv`, all, columns)
    } finally {
      setExporting(false)
    }
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
        <Select
          style={{ width: 180 }}
          placeholder={t('transactions.filters.poolPlaceholder')}
          allowClear
          showSearch
          optionFilterProp="label"
          options={poolOptions}
          value={filters.poolId}
          onChange={(v) => { setFilters((prev) => ({ ...prev, poolId: v as string })); setPage(0) }}
        />
        <RangePicker
          onChange={handleDateChange}
          format="YYYY-MM-DD"
          placeholder={[t('transactions.filters.dateFrom'), t('transactions.filters.dateTo')]}
        />
        <Button icon={<ReloadOutlined />} onClick={() => refetch()}>{t('transactions.filters.refresh')}</Button>
        <Button onClick={handleReset}>{t('transactions.filters.reset')}</Button>
        {/* CSV export — pulls the FULL filtered set (handleExportCsv loops the
            paged API), not just the page on screen; columns are the human
            symbols/pool the user sees, not raw UUIDs. */}
        <Button
          icon={<DownloadOutlined />}
          loading={exporting}
          onClick={handleExportCsv}
          disabled={(data?.totalElements ?? 0) === 0}
        >
          {t('transactions.filters.exportCsv')}
        </Button>
      </Space>

      <Table
        scroll={{ x: 'max-content' }}
        className="sber-table"
        loading={isLoading}
        dataSource={data?.content || []}
        rowKey="id"
        locale={{
          // isLoading guard: empty dataSource during the initial fetch must not flash
          // the "no transactions" copy. Filter-active check covers EVERY field of
          // TransactionFilters (review: a hardcoded field list silently missed poolId).
          emptyText: isLoading ? (
            <span aria-hidden="true" />
          ) : Object.values(filters).some((v) => v !== undefined && v !== null && v !== '') ? (
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
            responsive: ['md'] as const,
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
            responsive: ['md'] as const,
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
