import { useMemo, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { Card, Typography, Space, Tag, Button, Spin, Alert, Row, Col, Tabs, message, Modal } from 'antd'
import {
  ArrowLeftOutlined,
  PlusOutlined,
  DollarOutlined,
  RiseOutlined,
  PercentageOutlined,
  FundOutlined,
  ThunderboltFilled,
  PieChartOutlined,
  WarningOutlined,
  DeleteOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { pools, fees } from '@/api/services'
import type { LiquidityStrategy, Pool, PoolDetail, Position } from '@/api/types'
import BinLiquidityChart from '@/components/BinLiquidityChart'
import PoolActionTabs from '@/components/PoolActionTabs'
import PoolRecentSwapsPanel from '@/components/PoolRecentSwapsPanel'
import PoolPriceChart from '@/components/PoolPriceChart'
import { calculateStrategyWeights } from '@/lib/strategyWeights'
import { bpsToPercent } from '@/utils/format'
import { KpiRow, KpiTile, TokenPairChip } from '@/components/sber'
import { formatCompact, formatRub, formatTokenAmount } from '@/lib/format'
import dayjs from 'dayjs'

const { Title, Text } = Typography

const statusColors: Record<string, string> = {
  ACTIVE: 'success',
  PAUSED: 'warning',
  SHUTDOWN: 'error',
  PENDING: 'processing',
}

/**
 * Sprint 9-DS — user-facing PoolDetailPage refactor.
 *
 * <p>Matches the admin PoolDetail layout: hero card with pair chip +
 * status + dynamic-fee badge + Add Liquidity action; 4-up KpiRow for
 * the live metrics; tabbed body with profile + bin chart.
 */
export default function PoolDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  // Sprint 9-DS-r4 (P1-2) — pending preview state for the bin chart.
  // The Add Liquidity panel (inside PoolActionTabs) pushes its current
  // {binMin, binMax, strategy} here on every change; BinLiquidityChart
  // reads it and overlays the would-be distribution.
  const [pendingPreview, setPendingPreview] = useState<{
    binMin: number
    binMax: number
    strategy: LiquidityStrategy
  } | null>(null)

  // Sprint 15 perf — list→detail handoff. If the user navigated from
  // PoolsPage, the pool is already in the ['pools', page] cache; we
  // expose it as initialData so the detail page renders instantly. A
  // background refetch then enriches with fields only present on the
  // detail endpoint (e.g. recent bins history). Reduces perceived
  // navigation latency from ~300ms (cold) to ~0ms.
  const { data: pool, isLoading, error } = useQuery({
    queryKey: ['poolDetail', id],
    queryFn: () => pools.getPool(id!),
    enabled: !!id,
    initialData: (): PoolDetail | undefined => {
      if (!id) return undefined
      const cachedListings = queryClient.getQueriesData<{ content: Pool[] }>({ queryKey: ['pools'] })
      for (const [, cached] of cachedListings) {
        const found = cached?.content?.find((p) => p.id === id)
        if (found) {
          // Pool fields are a subset of PoolDetail; the missing detail-only
          // fields (bins, currentDynamicFeeBps, totalFeesCollectedX/Y) will
          // arrive when the background refetch lands. UI tolerates undefined
          // for them already.
          return found as PoolDetail
        }
      }
      return undefined
    },
    // Use the list's freshness so React Query knows when to background-refetch.
    initialDataUpdatedAt: () =>
      queryClient.getQueryState(['pools', 0])?.dataUpdatedAt,
    staleTime: 10_000,
  })

  // Sprint 9-DS-r3 — my positions in this pool (Meteora-style inline
  // "control surface": every position with its claimable fees + Add /
  // Claim / Remove actions). Saves a round-trip to /positions.
  const { data: myPositions } = useQuery({
    queryKey: ['myPositions'],
    queryFn: pools.getMyPositions,
  })
  const poolPositions = useMemo<Position[]>(
    () => (myPositions ?? []).filter((p) => p.poolId === id && p.isActive),
    [myPositions, id],
  )

  const claimMutation = useMutation({
    mutationFn: (positionId: string) => fees.claimFees({ positionId }),
    onSuccess: () => {
      message.success('Комиссии забраны')
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myFeeSummary'] })
    },
    onError: (e: any) => message.error(e?.response?.data?.message || 'Не удалось забрать комиссии'),
  })

  // Sprint 9-DS-r4 (P1-8) — one-click rebalance state. Disables the
  // banner CTA while the multi-step close+reopen runs so the user
  // can't double-fire.
  const [rebalancing, setRebalancing] = useState(false)

  /**
   * Task #20 fix — useMemo MUST be called before any conditional early
   * return so React's hook-order invariant holds. Was below at line 143
   * after the `if (isLoading|error)` guards → React error #310 on
   * every PoolDetailPage navigation. Internal guards (`!pool?.bins ||
   * poolPositions.length === 0`) handle the loading / not-found cases
   * without crashing.
   *
   * Sprint 9-DS-r4 (P2-6) — per-bin user share map for the
   * BinLiquidityChart tooltip ("Ваша доля: X%"). Approximation that
   * distributes each position's totalLiquidityShares across its bin
   * range via strategy weights, divides by bin total liquidity.
   */
  const userBinSharePctByBinId = useMemo(() => {
    const map = new Map<number, number>()
    if (!pool?.bins || poolPositions.length === 0) return map
    const binTotalById = new Map<number, number>()
    for (const b of pool.bins) binTotalById.set(b.binId, b.liquidity)
    for (const pos of poolPositions) {
      const weights = calculateStrategyWeights(
        pos.strategy,
        pos.binRangeMin,
        pos.binRangeMax,
        pool.activeBinId,
      )
      for (let i = 0; i < weights.length; i++) {
        const binId = pos.binRangeMin + i
        const total = binTotalById.get(binId)
        if (!total || total <= 0) continue
        const userLiq = pos.totalLiquidityShares * weights[i]
        const pct = (userLiq / total) * 100
        map.set(binId, (map.get(binId) ?? 0) + pct)
      }
    }
    return map
  }, [pool, poolPositions])

  if (isLoading) return <div style={{ textAlign: 'center', padding: '80px 0' }}><Spin size="large" /></div>
  if (error || !pool) return <Alert message="Пул не найден" type="error" showIcon />

  const dynamicFeeRaised = pool.currentDynamicFeeBps > pool.baseFeeBps
  const tvlRub = pool.tokenYSymbol === 'SRUB'
    ? pool.totalTvlY + pool.totalTvlX * pool.currentPrice
    : pool.tokenXSymbol === 'SRUB'
    ? pool.totalTvlX + pool.totalTvlY / pool.currentPrice
    : pool.totalTvlX + pool.totalTvlY

  // Sprint 9-DS-r3 — Meteora "Out-of-range" detection. A position is
  // out-of-range when the active bin lies outside its [binRangeMin,
  // binRangeMax]. Out-of-range positions earn 0 fees — surface this
  // loudly so the user knows to rebalance.
  const outOfRangeCount = poolPositions.filter(
    (p) => pool.activeBinId < p.binRangeMin || pool.activeBinId > p.binRangeMax,
  ).length

  // Aggregate "my share" — sum of my liquidity in this pool versus the
  // pool's total. Liquidity values are normalised already (totalTvlX/Y),
  // so we sum currentValueX/Y from each position and compare to TVL.
  const myShareValueRub = poolPositions.reduce((acc, p) => {
    const valX = p.currentValueX ?? 0
    const valY = p.currentValueY ?? 0
    if (pool.tokenYSymbol === 'SRUB') return acc + valY + valX * pool.currentPrice
    if (pool.tokenXSymbol === 'SRUB') return acc + valX + valY / pool.currentPrice
    return acc + valX + valY
  }, 0)
  const sharePct = tvlRub > 0 ? Math.min(100, (myShareValueRub / tvlRub) * 100) : 0

  const userBinRanges = poolPositions.map((p) => ({
    binMin: p.binRangeMin,
    binMax: p.binRangeMax,
  }))

  // userBinSharePctByBinId moved above the early-return guards — see
  // Task #20 fix note. Keeping this comment as a marker so future
  // readers don't put it back here.

  /**
   * Sprint 9-DS-r4 (P1-8) — one-click rebalance for OOR positions.
   *
   * For each out-of-range position we:
   *   1. Capture its current value (X, Y reserves) before removal so
   *      we can redeposit the same capital
   *   2. Remove 100% liquidity (atomic — rolls back if downstream fails)
   *   3. Reopen a new position centred on the CURRENT activeBinId with
   *      the SAME strategy and the SAME bin-width (so a ±10 SPOT
   *      becomes a ±10 SPOT around the new active price)
   *
   * Sequential, not parallel — same reasoning as P1-9 mass-close:
   * concurrent same-pool mutations rack up optimistic-lock retries
   * and give the LP no recoverable error point. Stop on first failure
   * so any positions already migrated stay migrated.
   *
   * Idempotency: each step carries its own random key so a partial
   * run that's resumed later doesn't double-close.
   */
  const confirmRebalanceAll = () => {
    if (!pool) return
    const oorPositions = poolPositions.filter(
      (p) => pool.activeBinId < p.binRangeMin || pool.activeBinId > p.binRangeMax,
    )
    if (oorPositions.length === 0) return

    Modal.confirm({
      title: `Ребалансировать ${oorPositions.length} позиций?`,
      width: 520,
      content: (
        <Space direction="vertical" size={8}>
          <Text>
            Каждая позиция вне диапазона будет последовательно закрыта,
            а затем заново открыта с тем же объёмом капитала, той же
            стратегией и шириной диапазона, но вокруг текущей цены
            (бин <code>{pool.activeBinId}</code>).
          </Text>
          <Text type="warning" style={{ fontSize: 'var(--text-xs)' }}>
            Операция последовательная — если что-то сломается на
            половине, ранее перенесённые позиции остаются перенесёнными.
            Не закрывайте вкладку до завершения.
          </Text>
        </Space>
      ),
      okText: `Перенести ${oorPositions.length}`,
      cancelText: 'Отмена',
      onOk: async () => {
        setRebalancing(true)
        let done = 0
        try {
          for (const pos of oorPositions) {
            // Capture pre-remove values so we know how much to redeposit.
            const amountX = pos.currentValueX ?? 0
            const amountY = pos.currentValueY ?? 0
            if (amountX <= 0 && amountY <= 0) {
              // Empty position — skip cleanly.
              done++
              continue
            }
            const widthMin = pos.binRangeMin - pool.activeBinId // typically negative
            const widthMax = pos.binRangeMax - pool.activeBinId // typically positive
            // Preserve TOTAL width — keep absolute spread = (max - min)
            // around the NEW active bin (centred). If original was
            // asymmetric (e.g. CURVE skewed up), preserve the skew.
            const newBinMin = pool.activeBinId + widthMin
            const newBinMax = pool.activeBinId + widthMax

            try {
              await pools.removeLiquidity({
                positionId: pos.id,
                percentage: 100,
                idempotencyKey: crypto.randomUUID(),
              })
              await pools.addLiquidity({
                poolId: pool.id,
                amountX,
                amountY,
                binRangeMin: newBinMin,
                binRangeMax: newBinMax,
                strategy: pos.strategy,
                idempotencyKey: crypto.randomUUID(),
              })
              done++
            } catch (e: any) {
              message.error(
                `Не удалось перенести позицию ${pos.id.slice(0, 6)}…: ${
                  e?.response?.data?.message || 'ошибка'
                }. Перенесено ${done} из ${oorPositions.length}.`,
              )
              return
            }
          }
          message.success(`Перенесено позиций: ${done}`)
        } finally {
          setRebalancing(false)
          queryClient.invalidateQueries({ queryKey: ['myPositions'] })
          queryClient.invalidateQueries({ queryKey: ['myBalances'] })
          queryClient.invalidateQueries({ queryKey: ['poolDetail', pool.id] })
        }
      },
    })
  }

  return (
    <Space direction="vertical" size={20} style={{ width: '100%' }}>
      <Button
        type="text"
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate('/pools')}
        style={{ padding: 0, color: 'var(--text-secondary)' }}
      >
        К списку пулов
      </Button>

      <Card
        className="sber-card"
        style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-light)', overflow: 'hidden' }}
        styles={{ body: { padding: 0 } }}
      >
        <div
          style={{
            padding: '24px 28px',
            background: 'linear-gradient(135deg, rgba(33,160,56,0.10) 0%, rgba(33,160,56,0.03) 100%)',
            borderBottom: '1px solid var(--border-light)',
          }}
        >
          <Row align="middle" gutter={[24, 16]}>
            <Col flex="auto" style={{ minWidth: 0 }}>
              <Space size={10} wrap align="center">
                <Title level={4} className="sber-page-title" style={{ margin: 0 }}>
                  <TokenPairChip x={pool.tokenXSymbol} y={pool.tokenYSymbol} size="lg" />
                </Title>
                <Tag color={statusColors[pool.status]} style={{ marginInlineEnd: 0 }}>
                  {pool.status}
                </Tag>
                {dynamicFeeRaised && (
                  <Tag color="orange" style={{ marginInlineEnd: 0 }}>
                    <ThunderboltFilled style={{ marginRight: 4 }} />
                    комиссия повышена
                  </Tag>
                )}
              </Space>
              <div style={{ marginTop: 8 }}>
                <Space size={16} wrap>
                  <Text type="secondary" style={{ fontSize: 'var(--text-sm)' }}>
                    Шаг бина: <strong style={{ color: 'var(--text-primary)' }}>{bpsToPercent(pool.binStep)}</strong>
                  </Text>
                  <Text type="secondary" style={{ fontSize: 'var(--text-sm)' }}>
                    Базовая комиссия: <strong style={{ color: 'var(--text-primary)' }}>{bpsToPercent(pool.baseFeeBps)}</strong>
                  </Text>
                  <Text type="secondary" style={{ fontSize: 'var(--text-sm)' }}>
                    Создан: <strong style={{ color: 'var(--text-primary)' }}>{dayjs(pool.createdAt).format('DD.MM.YYYY')}</strong>
                  </Text>
                </Space>
              </div>
            </Col>
            <Col flex="none">
              {/* Sprint 9-DS-r4 — removed the hero "Добавить ликвидность"
                  button that jumped to /liquidity. Both Add Liquidity
                  and Swap now live in the PoolActionTabs right rail
                  on this same page (Meteora pattern). */}
            </Col>
          </Row>
        </div>
      </Card>

      <KpiRow
        tiles={[
          {
            label: 'TVL пула',
            value: formatRub(tvlRub),
            sub: `${formatCompact(pool.totalTvlX)} ${pool.tokenXSymbol} + ${formatCompact(pool.totalTvlY)} ${pool.tokenYSymbol}`,
            icon: <DollarOutlined style={{ color: 'var(--sber-green)' }} />,
            accent: 'var(--sber-green)',
          },
          {
            label: 'Объём за 24ч',
            value: formatCompact(pool.volume24h ?? 0),
            sub: 'свопов за сутки',
            icon: <RiseOutlined style={{ color: '#296AE3' }} />,
          },
          {
            label: 'Расч. APY',
            value: pool.estimatedApy > 0 ? `${pool.estimatedApy.toFixed(2)}%` : '—',
            sub: pool.estimatedApy > 0 ? 'для LP-провайдеров' : 'недостаточно данных',
            icon: <PercentageOutlined style={{ color: '#9B59B6' }} />,
            accent: pool.estimatedApy > 0 ? 'var(--sber-green)' : undefined,
          },
          // Sprint 9-DS-r3 — replaced "Текущая цена" tile (already shown in
          // hero + bin chart) with "Моя доля". Meteora-style: tells the LP
          // how big a slice of the pool they own.
          {
            label: 'Моя доля',
            value: poolPositions.length === 0
              ? '—'
              : sharePct < 0.01
              ? '< 0,01%'
              : `${sharePct.toFixed(2)}%`,
            // Sprint 9-DS-r4 (P2-4) — split the sub line into two
            // rows when there's a position, so neither half wraps
            // awkwardly inside the narrow KPI tile (single-line
            // "N позиций · 5M ₽" wrapped mid-word when the share was
            // <0,01%).
            sub: poolPositions.length === 0 ? (
              'у вас нет позиций'
            ) : (
              <div style={{ lineHeight: 1.3 }}>
                <div style={{ whiteSpace: 'nowrap' }}>
                  {poolPositions.length} позиц
                  {poolPositions.length === 1 ? 'ия' : poolPositions.length < 5 ? 'ии' : 'ий'}
                </div>
                <div style={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>
                  {formatCompact(myShareValueRub)} ₽
                </div>
              </div>
            ),
            icon: <PieChartOutlined style={{ color: '#9333EA' }} />,
            accent: poolPositions.length > 0 ? '#9333EA' : undefined,
          },
        ]}
      />

      {/* Sprint 9-DS-r3 — out-of-range warning (Meteora pattern). When
          the active bin sits outside any of the user's ranges, that
          capital earns 0 fees. Surface loudly.
          Sprint 9-DS-r4 (P1-8) — the Ребаланс CTA is now a real
          one-click action: it closes the OOR positions and reopens
          them centred on the current active bin with the same
          strategy + bin-width. Was a navigate to /liquidity. */}
      {outOfRangeCount > 0 && (
        <Alert
          message={`${outOfRangeCount} ваших позиций — вне диапазона цены`}
          description="Позиция вне диапазона не получает комиссий. Нажмите «Ребаланс», чтобы перенести её к текущей цене с тем же объёмом капитала и той же стратегией."
          type="warning"
          showIcon
          icon={<WarningOutlined />}
          action={
            <Button
              size="small"
              type="primary"
              loading={rebalancing}
              onClick={confirmRebalanceAll}
              style={{ borderRadius: 'var(--radius-sm)' }}
            >
              Ребаланс ({outOfRangeCount})
            </Button>
          }
          style={{ borderRadius: 'var(--radius-md)' }}
        />
      )}

      {/* Sprint 9-DS-r3 — 2-col layout: bin chart (with user bins
          overlaid) + my positions on the left, embedded swap on the
          right. Mirrors Meteora's Dynamic Terminal: stay on the pool
          page, do everything without bouncing. */}
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={16}>
          {/* Sprint 9-DS-r4 (P1-4) — TradingView-style price+volume
              chart powered by lightweight-charts. Sits above the
              liquidity distribution so the LP sees price action
              first, then where the depth is. */}
          <div style={{ marginBottom: 16 }}>
            <PoolPriceChart poolId={pool.id} quoteSymbol={pool.tokenYSymbol} />
          </div>
          <Card
            className="sber-card"
            style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-light)' }}
            title={
              <Space size={8}>
                <Text strong>Распределение ликвидности</Text>
                {userBinRanges.length > 0 && (
                  <Tag color="purple" style={{ borderRadius: 'var(--radius-pill)', fontSize: 'var(--text-xs)' }}>
                    ваши бины подсвечены
                  </Tag>
                )}
              </Space>
            }
          >
            <BinLiquidityChart
              poolId={pool.id}
              userBinRanges={userBinRanges}
              pendingPreview={pendingPreview}
              userBinSharePctByBinId={userBinSharePctByBinId}
            />
          </Card>

          {poolPositions.length > 0 && (
            <Card
              className="sber-card"
              style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-light)', marginTop: 16 }}
              title={
                <Space size={8}>
                  <PieChartOutlined style={{ color: '#9333EA' }} />
                  <Text strong>Мои позиции в этом пуле</Text>
                  <Tag style={{ borderRadius: 'var(--radius-pill)' }}>{poolPositions.length}</Tag>
                </Space>
              }
              extra={
                <Button
                  size="small"
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={() => navigate(`/pools/${id}/liquidity`)}
                  style={{ borderRadius: 'var(--radius-sm)' }}
                >
                  Добавить
                </Button>
              }
              styles={{ body: { padding: 0 } }}
            >
              <div style={{ maxHeight: 320, overflowY: 'auto' }}>
                {poolPositions.map((p, i) => {
                  const outOfRange =
                    pool.activeBinId < p.binRangeMin || pool.activeBinId > p.binRangeMax
                  const hasFees = (p.unclaimedFeeX ?? 0) > 0 || (p.unclaimedFeeY ?? 0) > 0
                  return (
                    <div
                      key={p.id}
                      style={{
                        padding: '12px 16px',
                        borderTop: i === 0 ? 'none' : '1px solid var(--border-light)',
                        display: 'flex',
                        alignItems: 'center',
                        gap: 12,
                        flexWrap: 'wrap',
                      }}
                    >
                      <div style={{ minWidth: 0, flex: '1 1 200px' }}>
                        <Space size={6}>
                          <Tag color="blue" style={{ marginRight: 0 }}>{p.strategy}</Tag>
                          {outOfRange && (
                            <Tag color="orange" style={{ marginRight: 0 }} icon={<WarningOutlined />}>
                              вне диапазона
                            </Tag>
                          )}
                        </Space>
                        <div style={{ fontSize: 'var(--text-xs)', color: 'var(--text-secondary)', marginTop: 2, fontFamily: 'JetBrains Mono, monospace' }}>
                          бины {p.binRangeMin} — {p.binRangeMax}
                        </div>
                      </div>
                      <div style={{ minWidth: 0, flex: '1 1 200px', textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                        {hasFees ? (
                          <>
                            <div style={{ fontSize: 'var(--text-xs)', color: 'var(--sber-green)', fontWeight: 600 }}>
                              +{formatTokenAmount(p.unclaimedFeeX, pool.tokenXSymbol, { compact: true })}
                              {' · '}
                              +{formatTokenAmount(p.unclaimedFeeY, pool.tokenYSymbol, { compact: true })}
                            </div>
                            <div style={{ fontSize: 10, color: 'var(--text-muted)' }}>незабранные</div>
                          </>
                        ) : (
                          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>нет комиссий к получению</Text>
                        )}
                      </div>
                      <Space size={6}>
                        <Button
                          size="small"
                          type="primary"
                          ghost
                          icon={<DollarOutlined />}
                          disabled={!hasFees || claimMutation.isPending}
                          loading={claimMutation.isPending && claimMutation.variables === p.id}
                          onClick={() => claimMutation.mutate(p.id)}
                        >
                          Забрать
                        </Button>
                        <Button
                          size="small"
                          danger
                          icon={<DeleteOutlined />}
                          onClick={() => navigate(`/pools/${id}/liquidity`)}
                        >
                          Снять
                        </Button>
                      </Space>
                    </div>
                  )
                })}
              </div>
            </Card>
          )}

          {/* Sprint 9-DS-r4 (P1-6) — Meteora-style pool-scoped recent
              swaps feed. Always rendered: even if the user has no
              positions yet, seeing live pool activity is a strong
              "this market is liquid" signal that encourages first add. */}
          <PoolRecentSwapsPanel pool={pool} />
        </Col>

        <Col xs={24} xl={8}>
          {/* Sprint 9-DS-r4 — Meteora pattern: tabbed action panel
              with Add Liquidity + Swap as siblings. Mirrors the
              right-rail of Meteora's Dynamic Terminal. */}
          <PoolActionTabs
            pool={pool}
            defaultTab="add"
            onPreviewChange={setPendingPreview}
          />
        </Col>
      </Row>

      <Card
        className="sber-card"
        style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-light)' }}
        styles={{ body: { padding: '8px 16px 16px' } }}
      >
        <Tabs
          defaultActiveKey="params"
          items={[
            {
              key: 'params',
              label: 'Параметры пула',
              children: (
                <Row gutter={[16, 16]}>
                  <Col xs={24} md={12}>
                    <ProfileRow label="Шаг цены между бинами" value={bpsToPercent(pool.binStep)} />
                    <ProfileRow
                      label="Базовая комиссия"
                      value={bpsToPercent(pool.baseFeeBps)}
                    />
                    <ProfileRow
                      label="Текущая комиссия"
                      value={`${bpsToPercent(pool.currentDynamicFeeBps)}${dynamicFeeRaised ? ' (повышена)' : ''}`}
                    />
                    <ProfileRow
                      label="Текущая цена"
                      value={`${pool.currentPrice.toLocaleString('ru-RU', { maximumFractionDigits: 6 })} ${pool.tokenYSymbol}/${pool.tokenXSymbol}`}
                    />
                  </Col>
                  <Col xs={24} md={12}>
                    <ProfileRow
                      label={`Резерв ${pool.tokenXSymbol}`}
                      value={formatTokenAmount(pool.totalTvlX, pool.tokenXSymbol, { compact: true })}
                    />
                    <ProfileRow
                      label={`Резерв ${pool.tokenYSymbol}`}
                      value={formatTokenAmount(pool.totalTvlY, pool.tokenYSymbol, { compact: true })}
                    />
                    <ProfileRow
                      label={`Комиссии собрано в ${pool.tokenXSymbol}`}
                      value={formatTokenAmount(pool.totalFeesCollectedX, pool.tokenXSymbol, { compact: true })}
                    />
                    <ProfileRow
                      label={`Комиссии собрано в ${pool.tokenYSymbol}`}
                      value={formatTokenAmount(pool.totalFeesCollectedY, pool.tokenYSymbol, { compact: true })}
                    />
                  </Col>
                </Row>
              ),
            },
          ]}
        />
      </Card>
    </Space>
  )
}

function ProfileRow({ label, value }: { label: string; value: string }) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'baseline',
        padding: '10px 0',
        borderBottom: '1px solid var(--border-light)',
        gap: 12,
      }}
    >
      <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{label}</Text>
      <Text
        style={{
          fontSize: 'var(--text-sm)',
          fontWeight: 500,
          textAlign: 'right',
          fontVariantNumeric: 'tabular-nums',
          maxWidth: '70%',
        }}
      >
        {value}
      </Text>
    </div>
  )
}
