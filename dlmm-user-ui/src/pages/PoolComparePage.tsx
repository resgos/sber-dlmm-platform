import { useEffect, useMemo, useState } from 'react'
import {
  Card,
  Typography,
  Space,
  Select,
  Tag,
  Tooltip,
  Empty,
  Button,
  Row,
  Col,
  Statistic,
  Segmented,
  Table,
  message,
} from 'antd'
import {
  CloseCircleOutlined,
  PercentageOutlined,
  FundOutlined,
  RiseOutlined,
  ThunderboltFilled,
  InfoCircleOutlined,
  ClearOutlined,
  ShareAltOutlined,
  AppstoreOutlined,
  TableOutlined,
  CrownFilled,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { pools as poolsApi } from '@/api/services'
import type { Pool } from '@/api/types'
import { formatRub } from '@/components/StatCard'
import { bpsToPercent } from '@/utils/format'
import { computeProMetrics } from '@/lib/poolMetrics'

const { Title, Text } = Typography
const MAX_COMPARE = 3
const URL_PARAM = 'p'

/**
 * Sprint 10 (new feature) — Pool comparator.
 * Sprint 10 wave 3 polish — URL state (sharable links) + responsive
 * stack at small viewports + clear-all + "copy link" affordance.
 *
 * Lets the user pick 2 or 3 pools and see their headline metrics
 * side-by-side. Useful before committing capital — "is GAZP/SRUB
 * fee yield meaningfully better than SBER/SRUB at this point in
 * time?". No new backend endpoint needed (uses `/pools` listing).
 *
 * URL state: ?p=poolId1,poolId2,poolId3 — refresh-safe, shareable
 * via Slack. Updates lazily as the user adds/removes pools.
 *
 * The "winner per row" highlight is a UX hint, not investment
 * advice — the disclaimer alert at the bottom makes that explicit.
 */
export default function PoolComparePage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()

  // Initialise from URL — handles deep-link arrivals + refresh.
  const initialIds = useMemo(() => {
    const raw = searchParams.get(URL_PARAM)
    if (!raw) return []
    return raw.split(',').filter(Boolean).slice(0, MAX_COMPARE)
  }, []) // intentionally one-shot — URL is the source of truth on mount only

  const [selectedIds, setSelectedIds] = useState<string[]>(initialIds)

  // UI-CRITIQUE 2026-05-22 #8 — view-mode toggle. Cards стиль хорош
  // когда 1-2 пула; tabular mode выигрывает при 3 пулах + Dmitry-style
  // institutional users которые читают 5+ метрик подряд. localStorage-
  // persisted preference так что пользователь не переключает каждый
  // визит.
  const [viewMode, setViewMode] = useState<'cards' | 'table'>(() => {
    try {
      const raw = localStorage.getItem('dlmm.poolCompare.viewMode')
      if (raw === 'cards' || raw === 'table') return raw
    } catch { /* ignore */ }
    return 'cards'
  })
  useEffect(() => {
    try { localStorage.setItem('dlmm.poolCompare.viewMode', viewMode) } catch { /* ignore */ }
  }, [viewMode])

  // Push selection back to URL whenever it changes. Replace (not push)
  // so the browser back-button doesn't get spammed with every add/remove.
  useEffect(() => {
    if (selectedIds.length === 0) {
      // Clear the param entirely when empty so the URL stays clean.
      if (searchParams.has(URL_PARAM)) {
        const next = new URLSearchParams(searchParams)
        next.delete(URL_PARAM)
        setSearchParams(next, { replace: true })
      }
      return
    }
    const next = new URLSearchParams(searchParams)
    next.set(URL_PARAM, selectedIds.join(','))
    setSearchParams(next, { replace: true })
  }, [selectedIds, searchParams, setSearchParams])

  const { data: poolList } = useQuery({
    queryKey: ['pools', 0, 200],
    queryFn: () => poolsApi.getPools(0, 200),
  })

  const pools: Pool[] = poolList?.content ?? []
  const selected = useMemo(
    () => selectedIds.map((id) => pools.find((p) => p.id === id)).filter((p): p is Pool => !!p),
    [selectedIds, pools],
  )

  const addPool = (id: string): void => {
    if (selectedIds.includes(id)) {
      message.info('Этот пул уже в сравнении')
      return
    }
    if (selectedIds.length >= MAX_COMPARE) {
      message.warning(`Максимум ${MAX_COMPARE} пула. Уберите один, чтобы добавить новый.`)
      return
    }
    setSelectedIds([...selectedIds, id])
  }
  const removePool = (id: string): void => {
    setSelectedIds(selectedIds.filter((x) => x !== id))
  }
  const clearAll = (): void => {
    setSelectedIds([])
  }
  const copyShareLink = async (): Promise<void> => {
    // window.location.href has the current URL (already kept in sync by the
    // effect above). Stop using clipboard API if it's not available
    // (Safari in non-secure contexts) — fall back to a manual copy prompt.
    const url = window.location.href
    try {
      await navigator.clipboard.writeText(url)
      message.success('Ссылка для сравнения скопирована — отправьте коллегам')
    } catch {
      // eslint-disable-next-line no-alert
      window.prompt('Скопируйте ссылку вручную:', url)
    }
  }

  // For each comparable metric, identify the pool that "wins" — used to
  // tint the winner cell green. "Best" definition is metric-specific:
  //   APY  : highest
  //   24h volume : highest
  //   TVL  : highest
  //   Base fee bps : lowest (lower fees better for swappers)
  //   Bin step : lowest (tighter binning better for LP precision)
  const winners = useMemo(() => {
    if (selected.length < 2) return { apy: null, vol: null, tvlX: null, fee: null, binStep: null }
    const argmax = (key: keyof Pool, dir: 'max' | 'min') => {
      if (selected.length === 0) return null
      const sorted = [...selected].sort((a, b) =>
        dir === 'max'
          ? (b[key] as number) - (a[key] as number)
          : (a[key] as number) - (b[key] as number),
      )
      // If the top two are tied within 0.1%, no clear winner.
      const top = sorted[0][key] as number
      const second = sorted[1][key] as number
      if (top === 0 && second === 0) return null
      if (Math.abs(top - second) / Math.max(Math.abs(top), 1) < 0.001) return null
      return sorted[0].id
    }
    return {
      apy: argmax('estimatedApy', 'max'),
      vol: argmax('volume24h', 'max'),
      tvlX: argmax('totalTvlX', 'max'),
      fee: argmax('baseFeeBps', 'min'),
      binStep: argmax('binStep', 'min'),
    }
  }, [selected])

  const winnerStyle = { background: 'rgba(33, 160, 56, 0.06)', border: '1px solid rgba(33,160,56,0.25)' }

  const metricCell = (pool: Pool, value: string | number, isWinner: boolean): JSX.Element => (
    <div style={isWinner ? winnerStyle : undefined} key={pool.id}>
      <Statistic
        value={value as never}
        valueStyle={{
          fontSize: 'var(--text-md)',
          fontWeight: 600,
          color: isWinner ? 'var(--sber-green-dark)' : 'var(--text-primary)',
        }}
      />
    </div>
  )

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <div>
        <Title level={4} className="sber-page-title" style={{ marginBottom: 4 }}>
          Сравнение пулов
        </Title>
        <Text type="secondary">
          Выберите до {MAX_COMPARE} пулов, чтобы сравнить APY, объём, TVL и комиссии
        </Text>
      </div>

      <Card className="sber-card">
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12, flexWrap: 'wrap' }}>
            <Text strong>Добавить пул в сравнение</Text>
            {/* Sprint 10 wave 3 — actions visible only when there's
                something to act on. Share-link is the killer feature:
                shareable comparison via Slack/email. */}
            {selected.length > 0 && (
              <Space size={6} wrap>
                {/* UI-CRITIQUE #8 — view-mode toggle. Visible только
                    когда есть что показывать; иначе захламляет toolbar. */}
                <Segmented
                  size="small"
                  value={viewMode}
                  onChange={(v) => setViewMode(v as 'cards' | 'table')}
                  options={[
                    { value: 'cards', icon: <AppstoreOutlined />, label: 'Карты' },
                    { value: 'table', icon: <TableOutlined />, label: 'Таблица' },
                  ]}
                />
                <Tooltip title="Скопировать ссылку на это сравнение">
                  <Button
                    size="small"
                    icon={<ShareAltOutlined />}
                    onClick={copyShareLink}
                  >
                    Поделиться
                  </Button>
                </Tooltip>
                <Tooltip title="Очистить выбор">
                  <Button
                    size="small"
                    icon={<ClearOutlined />}
                    onClick={clearAll}
                    danger
                  >
                    Очистить
                  </Button>
                </Tooltip>
              </Space>
            )}
          </div>
          <Select
            placeholder="Найти пул по символам (например, SBER)"
            showSearch
            allowClear
            optionFilterProp="label"
            style={{ width: '100%', maxWidth: 480 }}
            onChange={(v) => v && addPool(v as string)}
            value={undefined as unknown as string} // reset after pick
            options={pools
              .filter((p) => !selectedIds.includes(p.id))
              .map((p) => ({
                value: p.id,
                label: `${p.tokenXSymbol} / ${p.tokenYSymbol}`,
              }))}
            disabled={selectedIds.length >= MAX_COMPARE}
          />
          {selectedIds.length >= MAX_COMPARE && (
            <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
              Максимум {MAX_COMPARE} пула одновременно. Уберите один, чтобы добавить новый.
            </Text>
          )}
        </Space>
      </Card>

      {selected.length === 0 ? (
        <Empty
          description="Выберите хотя бы один пул, чтобы начать сравнение"
          style={{ padding: 40 }}
        />
      ) : viewMode === 'table' ? (
        <Card className="sber-card">
          <ComparisonTable selected={selected} winners={winners} onOpenPool={(id) => navigate(`/pools/${id}`)} onRemove={removePool} />
        </Card>
      ) : (
        <Card className="sber-card">
          <Row gutter={[16, 16]}>
            {selected.map((p) => {
              // Sprint 10 wave 3 — responsive break-points so a 3-pool
              // comparison stacks vertically on phones and flows 2-up
              // on tablet portrait. Hard minWidth was forcing horizontal
              // scroll under 800px.
              return (
                <Col
                  key={p.id}
                  xs={24}
                  sm={selected.length === 1 ? 24 : 12}
                  md={Math.max(8, 24 / selected.length)}
                >
                  <Card
                    size="small"
                    title={
                      <Space>
                        <Tag color="green" style={{ borderRadius: 'var(--radius-pill)', fontWeight: 600 }}>
                          {p.tokenXSymbol} / {p.tokenYSymbol}
                        </Tag>
                        <Tag color={p.status === 'ACTIVE' ? 'success' : 'warning'} style={{ borderRadius: 'var(--radius-pill)' }}>
                          {p.status === 'ACTIVE' ? 'Активен' : p.status}
                        </Tag>
                      </Space>
                    }
                    extra={
                      <Button
                        type="text"
                        size="small"
                        icon={<CloseCircleOutlined />}
                        aria-label={`Убрать пул ${p.tokenXSymbol}/${p.tokenYSymbol} из сравнения`}
                        onClick={() => removePool(p.id)}
                      />
                    }
                  >
                    <Space direction="vertical" size={14} style={{ width: '100%' }}>
                      {/* APY */}
                      <div style={p.id === winners.apy ? winnerStyle : undefined}>
                        <Statistic
                          title={<Space size={6}><RiseOutlined />APY</Space>}
                          value={p.estimatedApy}
                          precision={2}
                          suffix="%"
                          valueStyle={{
                            color: p.id === winners.apy ? 'var(--sber-green-dark)' : 'var(--text-primary)',
                            fontVariantNumeric: 'tabular-nums',
                            fontWeight: p.id === winners.apy ? 700 : 600,
                          }}
                        />
                      </div>
                      {/* 24h volume in ₽ approx — assumes Y-side priced in ₽ if pair vs SRUB. */}
                      <div style={p.id === winners.vol ? winnerStyle : undefined}>
                        <Statistic
                          title={<Space size={6}><ThunderboltFilled />Объём 24ч</Space>}
                          value={p.volume24h}
                          formatter={(v) => formatRub(Number(v))}
                          valueStyle={{
                            color: p.id === winners.vol ? 'var(--sber-green-dark)' : 'var(--text-primary)',
                            fontVariantNumeric: 'tabular-nums',
                            fontWeight: p.id === winners.vol ? 700 : 600,
                          }}
                        />
                      </div>
                      {/* TVL X-side (token amount, not ₽ — easier comparison across heterogeneous pools). */}
                      <div style={p.id === winners.tvlX ? winnerStyle : undefined}>
                        <Statistic
                          title={<Space size={6}><FundOutlined />TVL {p.tokenXSymbol}</Space>}
                          value={p.totalTvlX}
                          formatter={(v) => Number(v).toLocaleString('ru-RU')}
                          valueStyle={{
                            color: p.id === winners.tvlX ? 'var(--sber-green-dark)' : 'var(--text-primary)',
                            fontVariantNumeric: 'tabular-nums',
                            fontWeight: p.id === winners.tvlX ? 700 : 600,
                          }}
                        />
                      </div>
                      {/* Base fee — winner is LOWEST. */}
                      <div style={p.id === winners.fee ? winnerStyle : undefined}>
                        <Statistic
                          title={<Space size={6}><PercentageOutlined />Базовая комиссия</Space>}
                          value={bpsToPercent(p.baseFeeBps)}
                          valueStyle={{
                            color: p.id === winners.fee ? 'var(--sber-green-dark)' : 'var(--text-primary)',
                            fontWeight: p.id === winners.fee ? 700 : 600,
                          }}
                        />
                      </div>
                      {/* Bin step — winner is LOWEST (tighter binning ≈ better LP precision). */}
                      <div style={p.id === winners.binStep ? winnerStyle : undefined}>
                        <Statistic
                          title={<Space size={6}>Шаг бина <Tooltip title="Меньше шаг = точнее размещение ликвидности, выше комиссии за поддержание"><InfoCircleOutlined /></Tooltip></Space>}
                          value={bpsToPercent(p.binStep)}
                          valueStyle={{
                            color: p.id === winners.binStep ? 'var(--sber-green-dark)' : 'var(--text-primary)',
                            fontWeight: p.id === winners.binStep ? 700 : 600,
                          }}
                        />
                      </div>

                      {/* Sprint 12 G-23 — pro metrics for institutional
                          users (Dmitry-driven feedback). Synthetic today;
                          real OHLCV swap-in Sprint 13. */}
                      <ProMetricsBlock pool={p} />

                      <Button block onClick={() => navigate(`/pools/${p.id}`)} type="primary" ghost>
                        Открыть пул
                      </Button>
                    </Space>
                  </Card>
                </Col>
              )
            })}
          </Row>
        </Card>
      )}

      {selected.length >= 2 && (
        <Card className="sber-card" size="small">
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
            <InfoCircleOutlined style={{ marginRight: 6 }} />
            Зелёная подсветка отмечает «победителя» в каждой строке. Это эвристический индикатор, а не инвестиционная рекомендация. APY рассчитывается по реализованной комиссии за последние 30 дней и может не отражать будущую доходность.
          </Text>
        </Card>
      )}
    </Space>
  )
}

/**
 * UI-CRITIQUE 2026-05-22 #8 — comparison table mode.
 *
 * Rows = metrics, columns = pools. Winners в каждой row подсвечены
 * crown иконкой + зелёный текст. Compact for institutional users
 * which need to scan 8+ metrics × 3 pools at once. Horizontal scroll
 * на mobile вместо visual squish которое cards-mode даёт.
 */
function ComparisonTable({
  selected,
  winners,
  onOpenPool,
  onRemove,
}: {
  selected: Pool[]
  winners: { apy: string | null; vol: string | null; tvlX: string | null; fee: string | null; binStep: string | null }
  onOpenPool: (id: string) => void
  onRemove: (id: string) => void
}) {
  // Compute pro metrics once per pool — they're deterministic-by-id so
  // calling computeProMetrics multiple times is safe but wasteful.
  const proById = useMemo(() => {
    const m = new Map<string, ReturnType<typeof computeProMetrics>>()
    for (const p of selected) m.set(p.id, computeProMetrics(p))
    return m
  }, [selected])

  // For pro metrics we compute winners locally — argmax / argmin on the
  // computed series. Same threshold convention as headline winners (skip
  // если top-2 совпадают в пределах 0.1%).
  const proWinners = useMemo(() => {
    const argextrema = (key: 'volatilityPct30d' | 'maxDrawdownPct' | 'sharpe', dir: 'max' | 'min'): string | null => {
      if (selected.length < 2) return null
      const sorted = [...selected].sort((a, b) => {
        const av = proById.get(a.id)![key]
        const bv = proById.get(b.id)![key]
        return dir === 'max' ? bv - av : av - bv
      })
      const top = proById.get(sorted[0].id)![key]
      const second = proById.get(sorted[1].id)![key]
      if (top === 0 && second === 0) return null
      if (Math.abs(top - second) / Math.max(Math.abs(top), 1) < 0.001) return null
      return sorted[0].id
    }
    return {
      // Volatility: lower is better for risk-conscious LP.
      volatility: argextrema('volatilityPct30d', 'min'),
      // Max drawdown: lower (closer to 0) is better.
      drawdown: argextrema('maxDrawdownPct', 'min'),
      // Sharpe: higher is better.
      sharpe: argextrema('sharpe', 'max'),
    }
  }, [selected, proById])

  interface MetricRow {
    label: string
    /** Per-pool cell renderer. Receives pool + isWinner flag. */
    render: (p: Pool, isWinner: boolean) => React.ReactNode
    /** Which pool id wins this row (null = no clear winner). */
    winnerId: string | null
  }

  const rows: MetricRow[] = [
    {
      label: 'APY',
      winnerId: winners.apy,
      render: (p, w) => <span>{p.estimatedApy.toFixed(2)}%{w && <CrownFilled style={{ marginInlineStart: 6, color: 'var(--sber-amber)' }} />}</span>,
    },
    {
      label: 'Объём 24ч',
      winnerId: winners.vol,
      render: (p, w) => <span>{formatRub(p.volume24h)}{w && <CrownFilled style={{ marginInlineStart: 6, color: 'var(--sber-amber)' }} />}</span>,
    },
    {
      label: 'TVL X',
      winnerId: winners.tvlX,
      render: (p, w) => <span>{p.totalTvlX.toLocaleString('ru-RU')}{w && <CrownFilled style={{ marginInlineStart: 6, color: 'var(--sber-amber)' }} />}</span>,
    },
    {
      label: 'Базовая комиссия',
      winnerId: winners.fee,
      render: (p, w) => <span>{bpsToPercent(p.baseFeeBps)}{w && <CrownFilled style={{ marginInlineStart: 6, color: 'var(--sber-amber)' }} />}</span>,
    },
    {
      label: 'Шаг бина',
      winnerId: winners.binStep,
      render: (p, w) => <span>{bpsToPercent(p.binStep)}{w && <CrownFilled style={{ marginInlineStart: 6, color: 'var(--sber-amber)' }} />}</span>,
    },
    {
      label: 'Волатильность 30д',
      winnerId: proWinners.volatility,
      render: (p, w) => {
        const m = proById.get(p.id)!
        return <span>{m.volatilityPct30d.toFixed(1)}%{w && <CrownFilled style={{ marginInlineStart: 6, color: 'var(--sber-amber)' }} />}</span>
      },
    },
    {
      label: 'Max drawdown',
      winnerId: proWinners.drawdown,
      render: (p, w) => {
        const m = proById.get(p.id)!
        return <span style={{ color: m.maxDrawdownPct > 10 ? 'var(--color-negative)' : undefined }}>
          −{m.maxDrawdownPct.toFixed(1)}%{w && <CrownFilled style={{ marginInlineStart: 6, color: 'var(--sber-amber)' }} />}
        </span>
      },
    },
    {
      label: 'Sharpe (vs 14% RFR)',
      winnerId: proWinners.sharpe,
      render: (p, w) => {
        const m = proById.get(p.id)!
        return <span style={{ color: m.sharpe > 1 ? 'var(--sber-green)' : m.sharpe < 0 ? 'var(--color-negative)' : undefined }}>
          {m.sharpe >= 0 ? '+' : ''}{m.sharpe.toFixed(2)}{w && <CrownFilled style={{ marginInlineStart: 6, color: 'var(--sber-amber)' }} />}
        </span>
      },
    },
  ]

  const columns = [
    {
      title: 'Метрика',
      dataIndex: 'label',
      key: 'label',
      fixed: 'left' as const,
      width: 200,
      render: (label: string) => <Text strong style={{ fontSize: 'var(--text-sm)' }}>{label}</Text>,
    },
    ...selected.map((p) => ({
      title: (
        <Space direction="vertical" size={2} align="start">
          <Space size={4}>
            <Tag color="green" style={{ borderRadius: 'var(--radius-pill)', fontWeight: 600, marginInlineEnd: 0 }}>
              {p.tokenXSymbol} / {p.tokenYSymbol}
            </Tag>
            <Button
              type="text"
              size="small"
              icon={<CloseCircleOutlined />}
              aria-label={`Убрать ${p.tokenXSymbol}/${p.tokenYSymbol}`}
              onClick={() => onRemove(p.id)}
            />
          </Space>
          <Button type="link" size="small" style={{ padding: 0, height: 'auto', fontSize: 'var(--text-xs)' }} onClick={() => onOpenPool(p.id)}>
            Открыть пул →
          </Button>
        </Space>
      ),
      dataIndex: p.id,
      key: p.id,
      align: 'right' as const,
      render: (_: unknown, row: MetricRow) => {
        const isWinner = row.winnerId === p.id
        return (
          <div
            style={{
              fontVariantNumeric: 'tabular-nums',
              fontWeight: isWinner ? 700 : 500,
              color: isWinner ? 'var(--brand-primary-strong)' : 'var(--text-primary)',
              padding: '4px 0',
            }}
          >
            {row.render(p, isWinner)}
          </div>
        )
      },
    })),
  ]

  return (
    <Table
      dataSource={rows}
      columns={columns}
      rowKey="label"
      pagination={false}
      scroll={{ x: 'max-content' }}
      size="small"
    />
  )
}

/**
 * Sprint 12 G-23 — pro metrics block per pool card. Three rows of
 * institutional-grade indicators (30d volatility, max drawdown,
 * Sharpe) plus a ⓘ that explains "это synthetic метрики". Sprint 13
 * swaps to real OHLCV reads.
 */
function ProMetricsBlock({ pool }: { pool: Pool }) {
  const m = computeProMetrics(pool)
  return (
    <div style={{ borderTop: '1px dashed var(--border-light)', paddingTop: 10, marginTop: 6 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
        <Text type="secondary" style={{ fontSize: 'var(--text-xs)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: 0.4 }}>
          Pro метрики
        </Text>
        {m.isSynthetic && (
          <Tooltip title="Synthetic метрики на базе APY + volume24h. Реальные исторические значения по OHLCV — Sprint 13.">
            <Tag color="default" style={{ fontSize: 10, borderRadius: 'var(--radius-pill)', marginInlineEnd: 0 }}>
              синтет.
            </Tag>
          </Tooltip>
        )}
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 'var(--text-xs)', marginBottom: 2 }}>
        <Text type="secondary">Волатильность 30д</Text>
        <Text strong style={{ fontVariantNumeric: 'tabular-nums' }}>{m.volatilityPct30d.toFixed(1)}%</Text>
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 'var(--text-xs)', marginBottom: 2 }}>
        <Text type="secondary">Max drawdown</Text>
        <Text strong style={{ fontVariantNumeric: 'tabular-nums', color: m.maxDrawdownPct > 10 ? 'var(--color-negative)' : 'var(--text-primary)' }}>
          −{m.maxDrawdownPct.toFixed(1)}%
        </Text>
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 'var(--text-xs)' }}>
        <Text type="secondary">Sharpe (vs 14% RFR)</Text>
        <Text strong style={{ fontVariantNumeric: 'tabular-nums', color: m.sharpe > 1 ? 'var(--sber-green)' : m.sharpe < 0 ? 'var(--color-negative)' : 'var(--text-primary)' }}>
          {m.sharpe >= 0 ? '+' : ''}{m.sharpe.toFixed(2)}
        </Text>
      </div>
    </div>
  )
}
