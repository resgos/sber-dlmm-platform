import { useMemo, useState } from 'react'
import { Table, Tag, Typography, Space, Button, Card, Modal, Slider, message, Row, Col, Tooltip, Dropdown, Alert } from 'antd'
import { DollarOutlined, DeleteOutlined, DownloadOutlined, PieChartOutlined, TrophyOutlined, WalletOutlined, ClearOutlined, RiseOutlined, FallOutlined, BellOutlined, DownOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { pools, fees, farming } from '@/api/services'
import type { Position, Pool, FeeHistoryEntry } from '@/api/types'
import { KpiRow, PageHeader, TokenPairChip } from '@/components/sber'
import { formatCompact, formatRub, formatTokenAmount } from '@/lib/format'
import PositionAlertsDrawer from '@/components/PositionAlertsDrawer'
import EmptyState from '@/components/EmptyState'
import HealthScoreBadge from '@/components/HealthScoreBadge'
import HealthScoreExplainer from '@/components/HealthScoreExplainer'
import { usePositionAlertWatcher } from '@/lib/usePositionAlertWatcher'
import { useAutoClaimWatcher } from '@/lib/useAutoClaimWatcher'
import { calculateHealth, type HealthScore } from '@/lib/positionHealth'
import { exportToCsv } from '@/lib/csvExport'
import { strategyLabel } from '@/lib/strategy'
import { apiErrorMessage } from '@/lib/apiError'
import { positionAlertsStore } from '@/store/positionAlertsStore'
import { useSyncExternalStore } from 'react'
import { Segmented } from 'antd'
import ModalHeader from '@/components/ModalHeader'
import dayjs from 'dayjs'
import { uuid } from '../lib/uuid'

const { Text } = Typography

// Sprint 9 — DLMM bins are evenly-spaced on a log scale: price at binId =
// basePrice * (1 + binStepBps/10000)^(binId - activeBinId). We render the
// raw bin range as a human-readable price range (in tokenY per 1 tokenX)
// because nobody outside the engineering team can mentally translate
// "8388603 — 8388613" into a price range. Falls back to the raw numbers
// if we don't have the surrounding pool's current price yet (first paint).
function formatBinPriceRange(
  binMin: number,
  binMax: number,
  activeBinId: number | null | undefined,
  binStepBps: number | null | undefined,
  currentPrice: number | null | undefined,
  quoteSymbol: string,
): string {
  if (!activeBinId || !binStepBps || !currentPrice) {
    return `${binMin} — ${binMax}`
  }
  const r = 1 + binStepBps / 10000
  const lo = currentPrice * Math.pow(r, binMin - activeBinId)
  const hi = currentPrice * Math.pow(r, binMax - activeBinId)
  const fmt = (n: number) =>
    n.toLocaleString('ru-RU', { maximumFractionDigits: n >= 100 ? 2 : 4 })
  return `${fmt(lo)} — ${fmt(hi)} ${quoteSymbol}`
}

export default function PositionsPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [removeModalPos, setRemoveModalPos] = useState<Position | null>(null)
  const [removePercent, setRemovePercent] = useState(100)
  const [alertsDrawerOpen, setAlertsDrawerOpen] = useState(false)
  // Sprint 10 (new feature) — show the alert count next to the bell
  // so the user knows whether they've configured any rules.
  const alertCount = useSyncExternalStore(
    positionAlertsStore.subscribe,
    () => positionAlertsStore.list().length,
    () => 0,
  )

  const { data: myPositions, isLoading } = useQuery({
    queryKey: ['myPositions'],
    queryFn: pools.getMyPositions,
  })

  // Sprint 9 — backend Position DTO doesn't carry tokenXSymbol /
  // tokenYSymbol / binStep / currentPrice / activeBinId, so the page
  // was rendering "/" for the pair and raw bin IDs for the range.
  // Pull the whole pool catalog (cheap, server-paginated) and join
  // by poolId on the client. The catalog is already cached by other
  // pages so this is usually a free read.
  const { data: poolPage } = useQuery({
    queryKey: ['pools', 0, 100],
    queryFn: () => pools.getPools(0, 100),
  })
  const poolById = useMemo(() => {
    const map = new Map<string, Pool>()
    for (const p of poolPage?.content ?? []) map.set(p.id, p)
    return map
  }, [poolPage])

  const { data: feeSummary } = useQuery({
    queryKey: ['myFeeSummary'],
    queryFn: fees.getMyFeeSummary,
  })

  const { data: feeHistory } = useQuery({
    queryKey: ['myFeeHistory'],
    queryFn: () => fees.getMyFeeHistory(0, 20),
  })

  const claimMutation = useMutation({
    mutationFn: ({ positionId, quoteOnly }: { positionId: string; quoteOnly?: boolean }) =>
      fees.claimFees({ positionId }, quoteOnly ?? false),
    onSuccess: () => {
      message.success(t('positions.messages.feesClaimed'))
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myFeeSummary'] })
      queryClient.invalidateQueries({ queryKey: ['myFeeHistory'] })
    },
    onError: (err: any) => message.error(apiErrorMessage(err, t('positions.messages.errorFallback'))),
  })

  // Sprint 17 — LP-farming rewards (SSPAS).
  const { data: farmRewards } = useQuery({ queryKey: ['myFarmRewards'], queryFn: farming.getMyRewards })
  const claimFarmMutation = useMutation({
    mutationFn: () => farming.claimRewards(),
    onSuccess: (claimed) => {
      message.success(t('positions.messages.farmClaimed', { amount: formatRub(claimed) }))
      queryClient.invalidateQueries({ queryKey: ['myFarmRewards'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
    },
    onError: () => message.error(t('positions.messages.farmClaimError')),
  })

  const removeMutation = useMutation({
    mutationFn: (positionId: string) =>
      pools.removeLiquidity({
        positionId,
        percentage: removePercent,
        idempotencyKey: uuid(),
      }),
    onSuccess: () => {
      message.success(t('positions.messages.liquidityRemoved'))
      setRemoveModalPos(null)
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
    },
    onError: (err: any) => message.error(apiErrorMessage(err, t('positions.messages.errorFallback'))),
  })

  // UI-CRITIQUE 2026-05-22 fix — wrap in useMemo so reference is
  // stable across re-renders. Без этого PositionAlertsWatcher /
  // AutoClaimWatcher useEffect re-fired каждый render, mutation
  // triggered state change, infinite loop → React error #185.
  const allActive = useMemo(
    () => (myPositions || []).filter((p: Position) => p.isActive),
    [myPositions],
  )

  // Sprint 10 wave 3 — health filter. Pre-compute each position's
  // health once and reuse for both the table column and the
  // filter; otherwise we'd be calling calculateHealth twice per row.
  const healthByPositionId = useMemo(() => {
    const map = new Map<string, HealthScore>()
    for (const p of allActive) {
      map.set(p.id, calculateHealth(p, poolById.get(p.poolId)))
    }
    return map
  }, [allActive, poolById])

  const [healthFilter, setHealthFilter] = useState<'all' | 'excellent' | 'good' | 'fair' | 'poor'>('all')
  const activePositions = useMemo(() => {
    if (healthFilter === 'all') return allActive
    return allActive.filter((p: Position) => healthByPositionId.get(p.id)?.band === healthFilter)
  }, [allActive, healthByPositionId, healthFilter])

  const totalUnclaimedX = activePositions.reduce((s: number, p: Position) => s + p.unclaimedFeeX, 0)
  const totalUnclaimedY = activePositions.reduce((s: number, p: Position) => s + p.unclaimedFeeY, 0)

  const positionsWithClaimableFees = activePositions.filter(
    (p) => p.unclaimedFeeX > 0 || p.unclaimedFeeY > 0,
  )

  /**
   * Sprint 9-DS-r4 (P1-9) — "Закрыть всё" mass-action, copied from
   * the HedgePage #6.14 pattern. Sequentially removes 100% of every
   * active position. Serial (not Promise.all) for two reasons:
   *  - same-pool concurrent removes would clobber each other under
   *    the optimistic-lock retry loop (#4.7), so we avoid the noise;
   *  - it gives the user a recoverable failure mode — first error
   *    stops the loop, all previously-closed positions stay closed
   *    (idempotency keys), and the remaining ones can be retried
   *    manually one-by-one.
   */
  const confirmRemoveAll = () => {
    if (activePositions.length === 0) return
    Modal.confirm({
      title: t('positions.confirmCloseAll.title', { count: activePositions.length }),
      width: 480,
      content: (
        <Space direction="vertical" size={8}>
          <Text>{t('positions.confirmCloseAll.body', { count: activePositions.length })}</Text>
          <Text type="warning" style={{ fontSize: 'var(--text-xs)' }}>
            {t('positions.confirmCloseAll.warning')}
          </Text>
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
            {t('positions.confirmCloseAll.note')}
          </Text>
        </Space>
      ),
      okText: t('positions.confirmCloseAll.okText', { count: activePositions.length }),
      okButtonProps: { danger: true },
      cancelText: t('common.cancel'),
      onOk: async () => {
        let closed = 0
        for (const pos of activePositions) {
          try {
            await pools.removeLiquidity({
              positionId: pos.id,
              percentage: 100,
              idempotencyKey: uuid(),
            })
            closed++
          } catch (e: any) {
            message.error(
              t('positions.messages.removeFailure', {
                id: pos.id.slice(0, 6),
                error: apiErrorMessage(e, t('positions.messages.errorWord')),
                done: closed,
                total: activePositions.length,
              }),
            )
            queryClient.invalidateQueries({ queryKey: ['myPositions'] })
            queryClient.invalidateQueries({ queryKey: ['myBalances'] })
            return
          }
        }
        message.success(t('positions.messages.closedCount', { count: closed }))
        queryClient.invalidateQueries({ queryKey: ['myPositions'] })
        queryClient.invalidateQueries({ queryKey: ['myBalances'] })
        queryClient.invalidateQueries({ queryKey: ['myFeeSummary'] })
        queryClient.invalidateQueries({ queryKey: ['myFeeHistory'] })
      },
    })
  }

  /**
   * Sprint 9-DS-r4 (P1-9) — companion to "Закрыть всё". Just claims
   * fees on every position that has unclaimed, without removing them.
   * Same serial-and-stop-on-first-failure shape as remove-all.
   */
  const confirmClaimAll = () => {
    if (positionsWithClaimableFees.length === 0) return
    Modal.confirm({
      title: t('positions.confirmClaimAll.title', { count: positionsWithClaimableFees.length }),
      width: 480,
      content: (
        <Space direction="vertical" size={8}>
          <Text>{t('positions.confirmClaimAll.body', { count: positionsWithClaimableFees.length })}</Text>
        </Space>
      ),
      okText: t('positions.confirmClaimAll.okText', { count: positionsWithClaimableFees.length }),
      cancelText: t('common.cancel'),
      onOk: async () => {
        let claimed = 0
        for (const pos of positionsWithClaimableFees) {
          try {
            await fees.claimFees({ positionId: pos.id })
            claimed++
          } catch (e: any) {
            message.error(
              t('positions.messages.claimFailure', {
                id: pos.id.slice(0, 6),
                error: apiErrorMessage(e, t('positions.messages.errorWord')),
                done: claimed,
                total: positionsWithClaimableFees.length,
              }),
            )
            queryClient.invalidateQueries({ queryKey: ['myPositions'] })
            queryClient.invalidateQueries({ queryKey: ['myBalances'] })
            queryClient.invalidateQueries({ queryKey: ['myFeeSummary'] })
            return
          }
        }
        message.success(t('positions.messages.claimedCount', { count: claimed }))
        queryClient.invalidateQueries({ queryKey: ['myPositions'] })
        queryClient.invalidateQueries({ queryKey: ['myBalances'] })
        queryClient.invalidateQueries({ queryKey: ['myFeeSummary'] })
        queryClient.invalidateQueries({ queryKey: ['myFeeHistory'] })
      },
    })
  }

  return (
    <Space direction="vertical" size={20} style={{ width: '100%' }}>
      <PageHeader
        title={t('positions.title')}
        subtitle={t('positions.subtitle')}
      />

      {/* Sprint 10 wave 3 — one-time onboarding banner explaining the
          new Health Score column. localStorage-persisted dismiss. */}
      <HealthScoreExplainer />

      <KpiRow
        tiles={[
          {
            label: t('positions.kpi.activePositions'),
            value: activePositions.length.toLocaleString('ru-RU'),
            sub: activePositions.length === 0 ? t('positions.kpi.activeNone') : t('positions.kpi.activeProvide'),
            icon: <PieChartOutlined style={{ color: '#9B59B6' }} />,
          },
          {
            label: t('positions.kpi.unclaimedFees'),
            value: formatRub(feeSummary?.totalUnclaimed ?? 0),
            sub: t('positions.kpi.readyToClaim'),
            icon: <WalletOutlined style={{ color: '#296AE3' }} />,
            accent: (feeSummary?.totalUnclaimed ?? 0) > 0 ? 'var(--sber-green)' : undefined,
          },
          {
            label: t('positions.kpi.totalEarned'),
            value: formatRub(feeSummary?.totalClaimed ?? 0),
            sub: t('positions.kpi.allTime'),
            icon: <TrophyOutlined style={{ color: '#F2994A' }} />,
          },
        ]}
      />

      {farmRewards && (farmRewards.totalUnclaimed > 0 || farmRewards.totalClaimed > 0) && (
        <Alert
          type="success"
          showIcon
          icon={<TrophyOutlined />}
          message={
            <Space size={8} wrap>
              <Text strong>{t('positions.farming.title')}</Text>
              <Text strong style={{ color: 'var(--sber-green)' }}>{formatRub(farmRewards.totalUnclaimed)}</Text>
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                {t('positions.farming.toClaim')}{farmRewards.totalClaimed > 0 ? ` · ${t('positions.farming.claimed', { amount: formatRub(farmRewards.totalClaimed) })}` : ''}
              </Text>
            </Space>
          }
          action={
            <Button
              size="small"
              type="primary"
              loading={claimFarmMutation.isPending}
              disabled={farmRewards.totalUnclaimed <= 0}
              onClick={() => claimFarmMutation.mutate()}
            >
              {t('positions.farming.claimButton')}
            </Button>
          }
          style={{ borderRadius: 'var(--radius-md)' }}
        />
      )}

      <Card
        className="sber-card"
        title={<Text strong>{t('positions.card.title')}</Text>}
        extra={
          // UI-CRITIQUE 2026-05-22 #2 fix — extras zone is now actions
          // only. Health filter (which is navigation / data selection)
          // moved out into its own row above the table. This separates
          // "what should the table show" from "what action should I
          // take on what's shown".
          <Space wrap>
            {/* Sprint 10 (new feature) — position alerts. Always
                visible (even with no positions) so the user can
                discover the feature; drawer shows the right CTA
                state internally. */}
            <Button
              size="small"
              icon={<BellOutlined />}
              onClick={() => setAlertsDrawerOpen(true)}
              aria-label={t('positions.card.alertsAriaLabel')}
            >
              {t('positions.card.alertsButton', { suffix: alertCount > 0 ? ` (${alertCount})` : '' })}
            </Button>
            {/* QW-1 (Batch #4) — CSV export of active positions. Useful
                for finance team weekly reporting + audit. */}
            <Button
              size="small"
              icon={<DownloadOutlined />}
              onClick={() => {
                if (activePositions.length === 0) return
                exportToCsv(
                  `dlmm-positions-${dayjs().format('YYYY-MM-DD')}.csv`,
                  activePositions,
                  [
                    { header: 'Position ID', accessor: (p: Position) => p.id },
                    { header: 'Pool ID', accessor: (p) => p.poolId },
                    { header: 'Pair', accessor: (p) => { const pl = poolById.get(p.poolId); return pl ? `${pl.tokenXSymbol}/${pl.tokenYSymbol}` : p.poolId } },
                    { header: 'Strategy', accessor: (p) => p.strategy },
                    { header: 'Bin Min', accessor: (p) => p.binRangeMin },
                    { header: 'Bin Max', accessor: (p) => p.binRangeMax },
                    { header: 'Liquidity Shares', accessor: (p) => p.totalLiquidityShares },
                    { header: 'Initial Deposit X', accessor: (p) => p.initialDepositX },
                    { header: 'Initial Deposit Y', accessor: (p) => p.initialDepositY },
                    { header: 'Current Value X', accessor: (p) => p.currentValueX },
                    { header: 'Current Value Y', accessor: (p) => p.currentValueY },
                    { header: 'Unclaimed Fee X', accessor: (p) => p.unclaimedFeeX },
                    { header: 'Unclaimed Fee Y', accessor: (p) => p.unclaimedFeeY },
                    { header: 'Active', accessor: (p) => p.isActive },
                    { header: 'Created At', accessor: (p) => p.createdAt },
                  ],
                )
              }}
              disabled={activePositions.length === 0}
            >
              {t('positions.card.csv')}
            </Button>
            {activePositions.length > 0 && (
              <>
                {/* Sprint 9-DS-r4 (P1-9) — mass-action pair: claim
                    fees on everything that has them, then close all if
                    the user wants to flatten the book. Mirrors the
                    HedgePage "Закрыть всё" pattern. */}
                <Button
                  size="small"
                  type="primary"
                  ghost
                  icon={<DollarOutlined />}
                  disabled={positionsWithClaimableFees.length === 0}
                  onClick={confirmClaimAll}
                >
                  {t('positions.card.claimAll', { count: positionsWithClaimableFees.length })}
                </Button>
                <Button
                  size="small"
                  danger
                  icon={<ClearOutlined />}
                  onClick={confirmRemoveAll}
                >
                  {t('positions.card.closeAll', { count: activePositions.length })}
                </Button>
              </>
            )}
          </Space>
        }
      >
        {/* UI-CRITIQUE 2026-05-22 #2 — filter zone, separated from
            actions zone in Card extras. */}
        {allActive.length > 1 && (
          <div style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{t('positions.healthFilter.label')}</Text>
            <Segmented
              size="small"
              value={healthFilter}
              onChange={(v) => setHealthFilter(v as typeof healthFilter)}
              options={[
                { label: t('positions.healthFilter.all', { count: allActive.length }), value: 'all' },
                { label: t('positions.healthFilter.excellent'), value: 'excellent' },
                { label: t('positions.healthFilter.good'), value: 'good' },
                { label: t('positions.healthFilter.fair'), value: 'fair' },
                { label: t('positions.healthFilter.poor'), value: 'poor' },
              ]}
            />
          </div>
        )}
        <Table
          scroll={{ x: 'max-content' }}
          className="sber-table"
          loading={isLoading}
          dataSource={activePositions}
          rowKey="id"
          pagination={false}
          size="middle"
          locale={{
            // While the query is in flight dataSource is [] for EVERYONE, so without
            // the isLoading guard the "no positions" onboarding CTA flashes under the
            // spinner for users who DO have positions (review).
            emptyText: isLoading ? (
              <span aria-hidden="true" />
            ) : allActive.length === 0 ? (
                <EmptyState
                  title={t('positions.empty.title')}
                  description={t('positions.empty.desc')}
                  cta={
                    <Button type="primary" icon={<DollarOutlined />} onClick={() => navigate('/pools')}>
                      {t('positions.empty.cta')}
                    </Button>
                  }
                />
              ) : (
                <EmptyState
                  size="compact"
                  title={t('positions.empty.filteredTitle')}
                  description={t('positions.empty.filteredDesc')}
                  secondary={
                    <Button type="link" onClick={() => setHealthFilter('all')}>
                      {t('positions.empty.filteredReset')}
                    </Button>
                  }
                />
              ),
          }}
          onRow={(record) => ({
            style: { cursor: 'pointer' },
            onClick: () => navigate(`/pools/${record.poolId}`),
          })}
          columns={[
            {
              title: t('positions.table.pool'),
              key: 'pool',
              width: 220,
              render: (_: unknown, r: Position) => {
                // Audit B3 — prefer the backend-resolved symbols (now self-describing);
                // fall back to the pool-catalogue join, then to a short id.
                const pool = poolById.get(r.poolId)
                const x = r.tokenXSymbol || pool?.tokenXSymbol
                const y = r.tokenYSymbol || pool?.tokenYSymbol
                if (x && y) return <TokenPairChip x={x} y={y} />
                return <Text type="secondary">{t('positions.table.poolFallback', { id: r.poolId.slice(0, 6) })}</Text>
              },
            },
            {
              // Sprint 10 (new feature) — Position Health Score column.
              // Single 0-100 number with a 3-factor tooltip breakdown
              // (range fit / fee earning / age). See lib/positionHealth.ts
              // for the weights + calibration notes.
              // Sprint 10 wave 3 — sortable so the user can flip to
              // "show me my worst positions first" with one click.
              title: <Tooltip title={t('positions.table.healthTooltip')}>{t('positions.table.health')}</Tooltip>,
              key: 'health',
              width: 110,
              align: 'center' as const,
              sorter: (a: Position, b: Position) => {
                const ah = healthByPositionId.get(a.id)?.total ?? 0
                const bh = healthByPositionId.get(b.id)?.total ?? 0
                return ah - bh
              },
              render: (_: unknown, r: Position) => <HealthScoreBadge position={r} pool={poolById.get(r.poolId)} />,
            },
            { title: t('positions.table.strategy'), dataIndex: 'strategy', render: (s: string) => <Tag color="green">{strategyLabel(s)}</Tag> },
            {
              title: t('positions.table.priceRange'),
              key: 'range',
              render: (_: unknown, r: Position) => {
                const pool = poolById.get(r.poolId)
                return (
                  <span style={{ fontVariantNumeric: 'tabular-nums', fontSize: 'var(--text-sm)' }}>
                    {formatBinPriceRange(
                      r.binRangeMin,
                      r.binRangeMax,
                      pool?.activeBinId,
                      pool?.binStep,
                      pool?.currentPrice,
                      pool?.tokenYSymbol ?? '',
                    )}
                  </span>
                )
              },
            },
            {
              // Sprint 9-DS-r4 (P1-10) — P&L column. Compares current
              // position value (server-supplied via PositionResponse)
              // against the original deposit (also server-supplied;
              // backed by the new initial_deposit_x/y columns added
              // in Liquibase 011). Computed in pair-quote currency
              // (tokenY units) since both sides convert to it via
              // the pool's current price. Legacy positions opened
              // before the schema migration carry initialDeposit=0
              // and show "—". Excludes fees (those have their own
              // column); pure mark-to-market P&L only.
              title: <Tooltip title={t('positions.table.pnlTooltip')}>{t('positions.table.pnl')}</Tooltip>,
              key: 'pnl',
              align: 'right' as const,
              render: (_: unknown, r: Position) => {
                const pool = poolById.get(r.poolId)
                const price = pool?.currentPrice ?? 0
                const ySym = pool?.tokenYSymbol ?? 'Y'
                const initialX = r.initialDepositX ?? 0
                const initialY = r.initialDepositY ?? 0
                const currentX = r.currentValueX ?? 0
                const currentY = r.currentValueY ?? 0

                if (initialX === 0 && initialY === 0) {
                  return (
                    <Tooltip title={t('positions.table.pnlLegacyTooltip')}>
                      <Text type="secondary">—</Text>
                    </Tooltip>
                  )
                }

                const initialQuote = initialY + initialX * price
                const currentQuote = currentY + currentX * price
                const pnlQuote = currentQuote - initialQuote
                const pnlPct = initialQuote > 0 ? (pnlQuote / initialQuote) * 100 : 0
                const positive = pnlQuote >= 0

                return (
                  <Space direction="vertical" size={0} style={{ alignItems: 'flex-end' }}>
                    <Text
                      strong
                      style={{
                        color: positive ? 'var(--sber-green)' : 'var(--color-negative)',
                        fontVariantNumeric: 'tabular-nums',
                        fontSize: 'var(--text-sm)',
                      }}
                    >
                      {positive ? <RiseOutlined /> : <FallOutlined />}{' '}
                      {positive ? '+' : ''}
                      {formatTokenAmount(Math.abs(pnlQuote), ySym, { compact: true, maxFractionDigits: 2 })}
                    </Text>
                    <Text
                      type="secondary"
                      style={{
                        fontSize: 'var(--text-xs)',
                        color: positive ? 'var(--sber-green)' : 'var(--color-negative)',
                        fontVariantNumeric: 'tabular-nums',
                      }}
                    >
                      {positive ? '+' : ''}{pnlPct.toFixed(2)}%
                    </Text>
                  </Space>
                )
              },
            },
            {
              title: t('positions.table.unclaimed'),
              key: 'unclaimed',
              align: 'right' as const,
              render: (_: unknown, r: Position) => {
                // Sprint 9 — was two columns ("Незабранные X", "Незабранные Y")
                // with raw numbers and no unit. Now one column showing the
                // actual token symbols. Renders "—" only when BOTH legs
                // are zero so a user sees "+120M SUSDT" even when only
                // one side has accrued fees.
                const pool = poolById.get(r.poolId)
                const xSym = pool?.tokenXSymbol ?? 'X'
                const ySym = pool?.tokenYSymbol ?? 'Y'
                if (r.unclaimedFeeX === 0 && r.unclaimedFeeY === 0) {
                  return <Text type="secondary">—</Text>
                }
                return (
                  <Space size={6} wrap style={{ justifyContent: 'flex-end' }}>
                    {r.unclaimedFeeX > 0 && (
                      <Tag color="green" style={{ marginInlineEnd: 0, borderRadius: 'var(--radius-pill)', padding: '0 8px', fontVariantNumeric: 'tabular-nums' }}>
                        +{formatTokenAmount(r.unclaimedFeeX, xSym, { compact: true, maxFractionDigits: 4 })}
                      </Tag>
                    )}
                    {r.unclaimedFeeY > 0 && (
                      <Tag color="green" style={{ marginInlineEnd: 0, borderRadius: 'var(--radius-pill)', padding: '0 8px', fontVariantNumeric: 'tabular-nums' }}>
                        +{formatTokenAmount(r.unclaimedFeeY, ySym, { compact: true, maxFractionDigits: 4 })}
                      </Tag>
                    )}
                  </Space>
                )
              },
            },
            {
              title: t('positions.table.createdAt'),
              dataIndex: 'createdAt',
              render: (d: string) => dayjs(d).format('DD.MM.YYYY'),
            },
            {
              title: t('positions.table.actions'),
              key: 'actions',
              render: (_: unknown, r: Position) => {
                // F-04 (UX-FINDINGS 2026-05-26) — explain WHY the Claim
                // button is disabled. Without this, users see greyed-out
                // buttons and assume the feature is broken.
                const noClaim = r.unclaimedFeeX === 0 && r.unclaimedFeeY === 0
                const claimBtn = (
                  <Dropdown.Button
                    size="small"
                    type="primary"
                    icon={<DownOutlined />}
                    loading={claimMutation.isPending}
                    disabled={noClaim}
                    onClick={() => claimMutation.mutate({ positionId: r.id, quoteOnly: false })}
                    menu={{
                      items: [{
                        key: 'quote',
                        label: t('positions.table.claimInQuote', { symbol: r.tokenYSymbol }),
                        onClick: () => claimMutation.mutate({ positionId: r.id, quoteOnly: true }),
                      }],
                    }}
                  >
                    <DollarOutlined /> {t('positions.table.claimButton')}
                  </Dropdown.Button>
                )
                return (
                  <Space onClick={(e) => e.stopPropagation()}>
                    {noClaim ? (
                      <Tooltip title={t('positions.table.noClaimTooltip')}>
                        <span>{claimBtn}</span>
                      </Tooltip>
                    ) : claimBtn}
                    <Button size="small" danger icon={<DeleteOutlined />}
                      onClick={() => { setRemoveModalPos(r); setRemovePercent(100) }}>
                      {t('positions.table.removeButton')}
                    </Button>
                  </Space>
                )
              },
            },
          ]}
        />
      </Card>

      {/* Fee history */}
      {feeHistory?.content && feeHistory.content.length > 0 && (
        <Card className="sber-card" title={<Text strong>{t('positions.feeHistory.title')}</Text>}>
          <Table
            scroll={{ x: 'max-content' }}
            className="sber-table"
            dataSource={feeHistory.content}
            rowKey="id"
            pagination={false}
            size="small"
            columns={[
              {
                title: t('positions.feeHistory.date'),
                dataIndex: 'accruedAt',
                render: (d: string) => dayjs(d).format('DD.MM.YYYY HH:mm'),
              },
              { title: t('positions.feeHistory.amount'), dataIndex: 'amount', align: 'right' as const, render: (v: number) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{formatCompact(v)}</span> },
              {
                title: t('positions.feeHistory.status'),
                dataIndex: 'claimed',
                render: (v: boolean) => <Tag color={v ? 'success' : 'processing'}>{v ? t('positions.feeHistory.claimed') : t('positions.feeHistory.accrued')}</Tag>,
              },
              {
                title: t('positions.feeHistory.claimedAt'),
                dataIndex: 'claimedAt',
                render: (d: string | null) => d ? dayjs(d).format('DD.MM.YYYY HH:mm') : '—',
              },
            ]}
          />
        </Card>
      )}

      <Modal
        title={<ModalHeader title={t('positions.removeModal.title')} severity="danger" />}
        open={!!removeModalPos}
        onCancel={() => setRemoveModalPos(null)}
        onOk={() => removeModalPos && removeMutation.mutate(removeModalPos.id)}
        confirmLoading={removeMutation.isPending}
        okText={t('positions.removeModal.okText')}
        cancelText={t('common.cancel')}
        okButtonProps={{ danger: true }}
      >
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Text>
            {t('positions.removeModal.body', {
              pair: (() => {
                const pool = removeModalPos ? poolById.get(removeModalPos.poolId) : undefined
                return pool ? `${pool.tokenXSymbol}/${pool.tokenYSymbol}` : ''
              })(),
            })}
          </Text>
          <Slider min={1} max={100} value={removePercent} onChange={setRemovePercent}
            marks={{ 25: '25%', 50: '50%', 75: '75%', 100: '100%' }} />
          <Text strong style={{ textAlign: 'center', display: 'block', fontSize: 24, color: 'var(--color-negative-strong)' }}>
            {removePercent}%
          </Text>
        </Space>
      </Modal>

      {/* Sprint 10 (new feature) — alert watcher + manager drawer.
          Hook fires once per myPositions/poolPage refresh; cooldown is
          handled inside the store. */}
      <PositionAlertsWatcherSlot positions={myPositions} pools={poolPage?.content ?? undefined} />
      <PositionAlertsDrawer
        open={alertsDrawerOpen}
        onClose={() => setAlertsDrawerOpen(false)}
        positions={activePositions}
      />
    </Space>
  )
}

/**
 * Sprint 10 (new feature) — tiny render-less helper.
 *
 * The watcher hooks (position alerts + auto-claim) must be called at
 * the top of a component (Rules of Hooks). Mounting them on a
 * sub-component keeps PositionsPage's own hook order stable and
 * gives us a clean place to thread props in.
 */
function PositionAlertsWatcherSlot({
  positions,
  pools,
}: {
  positions: Position[] | undefined
  pools: Pool[] | undefined
}) {
  const { t } = useTranslation()
  usePositionAlertWatcher(positions, pools)
  // Sprint 10 (new feature) — auto-claim watcher in the same helper
  // so we only mount one render-less child. Silent unless the user
  // has opted in via Profile → AutoClaimSettings.
  useAutoClaimWatcher(positions, ({ position }) => {
    // Surface a transient toast so the user knows a claim fired. The
    // Position DTO carries no token symbols — resolve the pair via the
    // pool catalog (same join the table uses) instead of rendering
    // "undefined/undefined". Show the claimed fee PER TOKEN: summing
    // unclaimedFeeX + unclaimedFeeY across two different tokens (as the
    // watcher's `amount` heuristic does) is a meaningless figure.
    const pl = pools?.find((p) => p.id === position.poolId)
    const pair = pl ? `${pl.tokenXSymbol}/${pl.tokenYSymbol}` : t('positions.messages.autoClaimPairFallback')
    const parts: string[] = []
    if (pl && position.unclaimedFeeX > 0) parts.push(`${position.unclaimedFeeX.toLocaleString('ru-RU')} ${pl.tokenXSymbol}`)
    if (pl && position.unclaimedFeeY > 0) parts.push(`${position.unclaimedFeeY.toLocaleString('ru-RU')} ${pl.tokenYSymbol}`)
    message.success(
      t('positions.messages.autoClaim', {
        pair,
        amount: parts.length ? parts.join(' + ') : t('positions.messages.autoClaimFeesFallback'),
      }),
      4,
    )
  })
  return null
}
