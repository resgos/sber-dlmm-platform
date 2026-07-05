import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Table, Tag, Typography, Space, Select, DatePicker, Button, Card, Grid, Pagination, Tooltip } from 'antd'
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

// 2026-06-17 — per-row "elevated fee" indicator. The effective swap fee rate
// (bps, written per swap) is compared to the pool's configured base fee: when it
// is materially higher the variable/dynamic fee kicked in (volatility) or the
// swap crossed several bins, so the trader paid above the sticker price. We tint
// the bps text and explain it in the tooltip instead of letting an expensive
// trade hide as a plain number. Thresholds are intentionally wide (×1.5 / ×3) so
// ordinary rounding noise around the base fee never flags.
export type FeeRateSeverity = 'normal' | 'elevated' | 'high'
export function classifyFeeRate(
  feeRateBps: number | null | undefined,
  baseFeeBps: number | null | undefined,
): FeeRateSeverity {
  if (feeRateBps == null || feeRateBps <= 0 || baseFeeBps == null || baseFeeBps <= 0) return 'normal'
  const ratio = feeRateBps / baseFeeBps
  if (ratio >= 3) return 'high'
  if (ratio >= 1.5) return 'elevated'
  return 'normal'
}
// Colour class (not AntD `type="danger"`): the dark theme force-overrides
// .ant-typography colour with !important, so the tint lives in sber-theme.css
// under a higher-specificity .fee-rate-* class. '' keeps the base secondary look.
const severityClass = (s: FeeRateSeverity): string =>
  s === 'high' ? 'fee-rate-high' : s === 'elevated' ? 'fee-rate-elevated' : ''

export default function TransactionsPage() {
  const { t } = useTranslation()
  // Mobile (<md): the wide transaction table forces horizontal scroll, so we
  // render a stacked card per row instead (same approach as PositionsPage).
  const screens = Grid.useBreakpoint()
  const isMobile = !screens.md
  // Deep-link support: /transactions?poolId=<id> opens pre-filtered to one pool
  // (e.g. the "История по пулу" link on the pool page). Read once on mount.
  const [searchParams] = useSearchParams()
  const [page, setPage] = useState(0)
  const [filters, setFilters] = useState<TransactionFilters>(() => {
    const poolId = searchParams.get('poolId')
    return poolId ? { poolId } : {}
  })
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
  // poolId -> base fee (bps) to flag rows whose effective fee ran above it. The
  // pool list is already fetched for the filter; an unknown poolId (not in the
  // first 100) just yields no badge (fail-open).
  const poolBaseFeeById = useMemo(() => {
    const m = new Map<string, number>()
    for (const p of poolList?.content ?? []) m.set(p.id, p.baseFeeBps)
    return m
  }, [poolList])

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
        { header: t('transactions.csv.feeRate'), accessor: (r) => r.feeRate ?? '' },
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
          // 2026-07-06 — one-click ranges for the common reporting windows.
          // Presets flow through the same onChange → the same UTC-instant
          // bound mapping (fcaa07f), so «Сегодня» is the user's true local day.
          presets={[
            { label: t('transactions.filters.presets.today'), value: [dayjs(), dayjs()] },
            { label: t('transactions.filters.presets.yesterday'), value: [dayjs().subtract(1, 'day'), dayjs().subtract(1, 'day')] },
            { label: t('transactions.filters.presets.week'), value: [dayjs().subtract(6, 'day'), dayjs()] },
            { label: t('transactions.filters.presets.month'), value: [dayjs().subtract(29, 'day'), dayjs()] },
          ]}
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

      {isMobile ? (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {(data?.content || []).map((r) => {
            const px = r.tokenInSymbol || (r.poolName ? r.poolName.split('/')[0] : undefined)
            const py = r.tokenOutSymbol || (r.poolName ? r.poolName.split('/')[1] : undefined)
            const feeBase = r.poolId ? poolBaseFeeById.get(r.poolId) : undefined
            const feeSev = classifyFeeRate(r.feeRate, feeBase)
            return (
              <Card
                key={r.id}
                size="small"
                className="sber-card"
                style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-light)' }}
                styles={{ body: { padding: 'var(--space-3)' } }}
              >
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                    <Tag color={txTypeColors[r.txType] || 'default'} style={{ marginInlineEnd: 0 }}>{txTypeLabel(r.txType)}</Tag>
                    <Tag color={statusColors[r.status] || 'default'} style={{ marginInlineEnd: 0 }}>{statusLabel(r.status)}</Tag>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                    {px || py ? <TokenPairChip x={px} y={py} /> : <Text type="secondary">—</Text>}
                    <Text type="secondary" style={{ fontSize: 'var(--text-xs)', whiteSpace: 'nowrap' }}>
                      {dayjs(r.createdAt).format('DD.MM.YYYY HH:mm')}
                    </Text>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                    <Text style={{ fontVariantNumeric: 'tabular-nums', fontSize: 'var(--text-sm)' }}>
                      {formatTokenAmount(r.amountIn, r.tokenInSymbol)} → {formatTokenAmount(r.amountOut, r.tokenOutSymbol)}
                    </Text>
                    {r.feeAmount != null && r.feeAmount > 0 && (
                      <Text type="secondary" className={severityClass(feeSev)} style={{ fontSize: 'var(--text-xs)', whiteSpace: 'nowrap' }}>
                        {t('transactions.table.fee')}: {formatTokenAmount(r.feeAmount, r.tokenInSymbol, { maxFractionDigits: 6 })}
                        {r.feeRate != null && r.feeRate > 0 ? ` · ${+r.feeRate.toFixed(2)} bps${feeSev !== 'normal' ? ' ⚠' : ''}` : ''}
                      </Text>
                    )}
                  </div>
                </Space>
              </Card>
            )
          })}
          {!isLoading && (data?.content || []).length === 0 && (
            Object.values(filters).some((v) => v !== undefined && v !== null && v !== '') ? (
              <EmptyState
                size="compact"
                title={t('transactions.empty.filteredTitle')}
                description={t('transactions.empty.filteredDesc')}
                secondary={<Button type="link" onClick={handleReset}>{t('transactions.empty.filteredReset')}</Button>}
              />
            ) : (
              <EmptyState title={t('transactions.empty.title')} description={t('transactions.empty.desc')} />
            )
          )}
          {(data?.totalElements || 0) > pageSize && (
            <div style={{ textAlign: 'center', marginTop: 'var(--space-2)' }}>
              <Pagination
                simple
                current={page + 1}
                pageSize={pageSize}
                total={data?.totalElements || 0}
                onChange={(p) => setPage(p - 1)}
              />
            </div>
          )}
        </Space>
      ) : (
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
            render: (v: number | null, r: Transaction) => {
              const base = r.poolId ? poolBaseFeeById.get(r.poolId) : undefined
              const sev = classifyFeeRate(r.feeRate, base)
              return (
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end' }}>
                  <span style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                    {formatTokenAmount(v, r.tokenInSymbol, { maxFractionDigits: 6 })}
                  </span>
                  {r.feeRate != null && r.feeRate > 0 && (
                    <Tooltip title={sev !== 'normal' && base
                      ? t('transactions.table.feeRateElevatedTooltip', { base, actual: +r.feeRate.toFixed(2), ratio: +(r.feeRate / base).toFixed(1) })
                      : t('transactions.table.feeRateTooltip')}>
                      <Text type="secondary" className={severityClass(sev)} style={{ fontSize: 'var(--text-xs)', fontVariantNumeric: 'tabular-nums', cursor: 'help' }}>
                        {+r.feeRate.toFixed(2)} bps{sev !== 'normal' ? ' ⚠' : ''}
                      </Text>
                    </Tooltip>
                  )}
                </div>
              )
            },
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
      )}
    </Space>
  )
}
