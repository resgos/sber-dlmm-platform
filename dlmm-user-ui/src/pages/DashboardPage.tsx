import { useMemo, useSyncExternalStore } from 'react'
import { Row, Col, Card, Table, Tag, Space, Typography, Spin, Alert, Button, Tooltip } from 'antd'
import {
  WalletOutlined,
  PieChartOutlined,
  DollarOutlined,
  TrophyOutlined,
  ArrowRightOutlined,
  SwapOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { balances, pools, fees, transactions, oracle, tokens as tokensApi } from '@/api/services'
import { rowButtonProps } from '@/lib/a11y'
import TokenIcon from '@/components/TokenIcon'
import StatCard, { formatRub } from '@/components/StatCard'
import SpasiboWidget from '@/components/SpasiboWidget'
import MarketTicker from '@/components/MarketTicker'
import { uiPrefStore } from '@/store/uiPrefStore'
import { DASHBOARD_TILE_PALETTE } from '@/styles/palette'
import type { TokenBalance, Position, Transaction, TokenPrice, Pool, Token } from '@/api/types'
import dayjs from 'dayjs'

const { Title, Text } = Typography

// Sprint 9 — anchor of all rub-pricing. Every other token's RUB value
// comes from the SRUB-paired DLMM pool's currentPrice. price-oracle
// is wired but doesn't cover the full catalogue in dev, so portfolio
// totals were rendering as 0₽ even when the user held millions of
// SUSDT. Pool-derived prices are always live for any token that
// trades against SRUB, which is the whole catalogue today.
const BASE_SYMBOL = 'SRUB'

// Tag colours by code; the human label is resolved at render via
// t('dashboard.txType.<CODE>') / t('dashboard.txStatus.<CODE>').
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

export default function DashboardPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  // SM-01 — in Simple mode the primary "trade" CTAs point at the simple
  // buy/sell+LP surface instead of the Pro Swap page.
  const prefs = useSyncExternalStore(uiPrefStore.subscribe, uiPrefStore.getSnapshot)
  const tradeRoute = prefs.simpleMode ? '/simple' : '/swap'

  const { data: myBalances, isLoading: loadingBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })

  const { data: myPositions, isLoading: loadingPositions } = useQuery({
    queryKey: ['myPositions'],
    queryFn: pools.getMyPositions,
  })

  const { data: feeSummary } = useQuery({
    queryKey: ['myFeeSummary'],
    queryFn: fees.getMyFeeSummary,
  })

  const { data: recentTx } = useQuery({
    queryKey: ['myTransactions', 0, 5],
    queryFn: () => transactions.getMyTransactions(0, 5),
  })

  const { data: prices } = useQuery({
    queryKey: ['tokenPrices'],
    queryFn: oracle.getPrices,
    refetchInterval: 30000,
  })

  // Sprint 9 — pull pools + tokens so we can (a) join Position/Transaction
  // to pair symbols (same fix as elsewhere in the audit), and (b) derive
  // RUB prices from pool.currentPrice when the oracle is silent.
  const { data: poolPage } = useQuery({
    queryKey: ['pools', 0, 100],
    queryFn: () => pools.getPools(0, 100),
  })
  const { data: tokenPage } = useQuery({
    queryKey: ['tokens'],
    queryFn: () => tokensApi.getTokens(0, 200),
  })

  const poolById = useMemo(() => {
    const m = new Map<string, Pool>()
    for (const p of poolPage?.content ?? []) m.set(p.id, p)
    return m
  }, [poolPage])
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

  // Sprint 9 — derive per-token RUB price from the SRUB-paired pool when
  // the oracle is silent. For a pool tokenX/SRUB the on-chain currentPrice
  // is "1 tokenX = N SRUB", which is exactly what we need. Falls back to
  // the oracle, then to 1 if the token IS SRUB.
  const oraclePriceMap = useMemo(() => {
    const m = new Map<string, number>()
    for (const p of prices ?? []) m.set(p.symbol, p.price)
    return m
  }, [prices])
  const rubPriceBySymbol = useMemo(() => {
    const m = new Map<string, number>()
    m.set(BASE_SYMBOL, 1)
    for (const pool of poolPage?.content ?? []) {
      if (pool.tokenYSymbol === BASE_SYMBOL) {
        // tokenX is the foreign asset, currentPrice = SRUB per 1 tokenX.
        m.set(pool.tokenXSymbol, pool.currentPrice)
      } else if (pool.tokenXSymbol === BASE_SYMBOL) {
        // SRUB is tokenX, so 1 tokenY = 1/currentPrice SRUB.
        if (pool.currentPrice > 0) {
          m.set(pool.tokenYSymbol, 1 / pool.currentPrice)
        }
      }
    }
    // Oracle wins for any token it actually covers (some have ЦБ РФ /
    // MOEX feeds the pool doesn't reflect yet).
    for (const [sym, price] of oraclePriceMap) m.set(sym, price)
    return m
  }, [poolPage, oraclePriceMap])

  const totalBalanceRub = (myBalances || []).reduce((sum: number, b: TokenBalance) => {
    const price = rubPriceBySymbol.get(b.symbol) ?? 0
    return sum + (b.available + b.locked) * price
  }, 0)

  // HOT-2 fix — wrap in useMemo so reference is stable across renders
  // (same shape as PositionsPage). Also handles the render race:
  // myPositions starts undefined → filter returns [] → hero shows "0
  // позиций в работе". With loadingPositions guard at the call-site
  // we now render "—" until data lands.
  const activePositions = useMemo(
    () => (myPositions || []).filter((p: Position) => p.isActive),
    [myPositions],
  )

  if (loadingBalances) {
    return <div style={{ textAlign: 'center', padding: '80px 0' }}><Spin size="large" /></div>
  }

  const totalEarned = (feeSummary?.totalClaimed ?? 0) + (feeSummary?.totalUnclaimed ?? 0)
  const earnedDelta = totalBalanceRub > 0 ? (totalEarned / totalBalanceRub) * 100 : 0

  // Sprint 9 — compact hero spec lifted from the Claude Design
  // user-dashboard mockup (docs/design/user-dashboard-claude-design/).
  // The mockup folds the 4 stat tiles INTO the hero as inline
  // sub-metrics divided by hairline borders, so the page stops with
  // the duplicate "Общий баланс" tile that just repeats the hero
  // number, and saves ~120px of vertical space for the live feeds
  // below.
  // Batch #6 unit 4 — KPI tile tooltips. Each label gets a Tooltip
  // explaining the metric — new users (Анна persona) часто не понимают
  // что значит "TVL" / "fee accrual" / "позиции в работе". The tooltip
  // copy is passed explicitly per tile so it stays i18n-keyed instead
  // of being looked up by the (now translated) label text.
  const heroSubMetric = (label: string, value: React.ReactNode, sub: React.ReactNode, tooltip?: string) => (
    <Col
      flex="1 1 0"
      style={{
        padding: '0 22px',
        borderLeft: '1px solid rgba(255,255,255,0.22)',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        gap: 4,
        minWidth: 0,
      }}
    >
      <div
        style={{
          fontSize: 'var(--text-xs)',
          color: 'rgba(255,255,255,0.7)',
          letterSpacing: '0.04em',
          textTransform: 'uppercase',
          fontWeight: 500,
        }}
      >
        {tooltip ? (
          <Tooltip title={tooltip}>
            <span style={{ cursor: 'help', borderBottom: '1px dotted rgba(255,255,255,0.4)' }}>
              {label}
            </span>
          </Tooltip>
        ) : label}
      </div>
      <div
        style={{
          fontSize: 'var(--text-lg)',
          fontWeight: 600,
          // HOT-1-followup — text on brand-green hero must stay white
          // in dark mode (was var(--bg-card) which flipped to #131820).
          color: 'var(--text-on-brand)',
          lineHeight: 1.1,
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        {value}
      </div>
      <div
        style={{
          fontSize: 'var(--text-xs)',
          color: 'rgba(255,255,255,0.65)',
        }}
      >
        {sub}
      </div>
    </Col>
  )

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      {/* Hero — single bank-grade lockup. Portfolio number on the left,
          4 inline sub-metrics on the right divided by hairline borders.
          Replaces the previous hero + 4-up StatCard row (one of which
          just duplicated the portfolio total). Pattern from the Claude
          Design redesign at docs/design/user-dashboard-claude-design/. */}
      <div className="sber-hero" style={{ padding: '20px 24px' }}>
        <Row gutter={0} align="middle" wrap={false} style={{ flexWrap: 'wrap' }}>
          <Col flex="0 0 320px" style={{ padding: '0 20px 0 4px', minWidth: 240 }}>
            <div
              style={{
                fontSize: 'var(--text-xs)',
                color: 'rgba(255,255,255,0.75)',
                letterSpacing: '0.04em',
                textTransform: 'uppercase',
                fontWeight: 500,
                marginBottom: 6,
              }}
            >
              {t('dashboard.portfolioLabel')}
            </div>
            <div
              style={{
                // UI-CRITIQUE 2026-05-22 #3 — hero value harmonised to
                // the new --text-display-ish 28 (was 32). CSS class
                // .sber-hero-value already enforces 36px via !important
                // — this inline только для случаев когда class не применён.
                fontSize: 'var(--text-xl)',
                fontWeight: 700,
                // HOT-1-followup — hero value on green BG must stay
                // white in dark mode (was var(--bg-card)).
                color: 'var(--text-on-brand)',
                lineHeight: 'var(--leading-tight)',
                letterSpacing: '-0.015em',
                fontVariantNumeric: 'tabular-nums',
                marginBottom: 8,
              }}
            >
              {formatRub(totalBalanceRub)}
            </div>
            <Space size={10}>
              <Button size="middle" onClick={() => navigate(tradeRoute)}
                style={{ background: 'rgba(255,255,255,0.18)', borderColor: 'rgba(255,255,255,0.35)', color: 'var(--text-on-brand)' }}>
                {prefs.simpleMode ? t('dashboard.hero.buySell') : t('dashboard.hero.swap')}
              </Button>
              <Button size="middle" onClick={() => navigate('/pools')}
                // HOT-1-followup — white button on green hero must
                // stay white in dark mode (was var(--bg-card) which
                // flipped to #131820 = invisible chip on green).
                style={{ background: 'var(--text-on-brand)', borderColor: 'var(--text-on-brand)', color: 'var(--sber-green-dark)', fontWeight: 600 }}>
                {t('dashboard.hero.toPools')} <ArrowRightOutlined />
              </Button>
            </Space>
          </Col>

          {heroSubMetric(
            t('dashboard.tiles.activePositions'),
            // HOT-2 fix — show "—" while myPositions query is in
            // flight; otherwise the hero rendered "0 позиций в работе"
            // for a render-cycle before data arrived (observed during
            // UI walkthrough 2026-05-22: Dashboard said 0, /positions
            // said 11, because balances/positions queries race and
            // balances resolves first).
            loadingPositions ? '—' : activePositions.length,
            <span style={{ color: 'rgba(255,255,255,0.65)' }}>
              {loadingPositions
                ? t('dashboard.tiles_sub.positionsLoading')
                : t('dashboard.tiles_sub.positionsInWork', { count: activePositions.length })}
            </span>,
            t('dashboard.hero.tooltips.activePositions'),
          )}
          {heroSubMetric(
            t('dashboard.tiles.unclaimedFees'),
            formatRub(feeSummary?.totalUnclaimed ?? 0),
            (feeSummary?.totalUnclaimed ?? 0) > 0 ? (
              <span style={{ color: 'var(--text-on-brand)', fontWeight: 500 }}>{t('dashboard.tiles_sub.claimNow')}</span>
            ) : (
              // F-05 (UX-FINDINGS 2026-05-26) — "пока ничего не начислено"
              // звучит как "не работает". Differentiate: если активных
              // позиций нет — "откройте позицию"; если есть — "fee accrual
              // обновляется ~5 мин" (правда — backend job runs every 5min).
              activePositions.length > 0 ? (
                <span>{t('dashboard.tiles_sub.feeAccrualRefreshing')}</span>
              ) : (
                <span>{t('dashboard.tiles_sub.openPositionToEarn')}</span>
              )
            ),
            t('dashboard.hero.tooltips.unclaimedFees'),
          )}
          {heroSubMetric(
            t('dashboard.tiles.totalEarned'),
            formatRub(totalEarned),
            earnedDelta > 0 ? (
              <span>{t('dashboard.tiles_sub.earnedDelta', { value: earnedDelta.toFixed(2) })}</span>
            ) : (
              <span>{t('dashboard.tiles_sub.startEarning')}</span>
            ),
            t('dashboard.hero.tooltips.totalEarned'),
          )}
        </Row>
      </div>

      {/* Sprint 9 — Activity + Quick actions split row, lifted from the
          Claude Design redesign (docs/design/user-dashboard-claude-design/).
          Three blocks below the hero so the dashboard reads as "live
          system" instead of "static snapshot": (1) SberSpasibo loyalty
          stays as a narrow promo on the left, (2) Quick actions in the
          middle — the three shortcuts a treasurer hits most often,
          (3) Recent activity teaser on the right that's also a link to
          the full transactions page. */}
      <MarketTicker prices={prices} />

      <Row gutter={[16, 16]} align="stretch">
        <Col xs={24} md={12} lg={8}>
          <SpasiboWidget />
        </Col>

        <Col xs={24} md={12} lg={8}>
          <Card
            className="sber-card"
            title={<Text strong>{t('dashboard.quickActions.title')}</Text>}
            style={{ height: '100%' }}
          >
            <Space direction="vertical" size={10} style={{ width: '100%' }}>
              <Button
                type="primary"
                size="large"
                block
                icon={<SwapOutlined />}
                onClick={() => navigate(tradeRoute)}
                style={{ justifyContent: 'flex-start', textAlign: 'left', fontWeight: 600 }}
              >
                {prefs.simpleMode ? t('dashboard.quickActions.buySellTokens') : t('dashboard.quickActions.swapUsdt')}
              </Button>
              <Button
                size="large"
                block
                icon={<DollarOutlined />}
                onClick={() => navigate('/hedge')}
                style={{ justifyContent: 'flex-start', textAlign: 'left' }}
              >
                {t('dashboard.quickActions.openHedge')}
              </Button>
              <Button
                size="large"
                block
                icon={<PieChartOutlined />}
                onClick={() => navigate('/pools')}
                style={{ justifyContent: 'flex-start', textAlign: 'left' }}
              >
                {t('dashboard.quickActions.addLiquidity')}
              </Button>
            </Space>
          </Card>
        </Col>

        <Col xs={24} md={24} lg={8}>
          <Card
            className="sber-card"
            title={<Text strong>{t('dashboard.recentOps.title')}</Text>}
            extra={
              <a onClick={() => navigate('/transactions')} style={{ color: 'var(--sber-green)', fontSize: 'var(--text-sm)', cursor: 'pointer' }}>
                {t('dashboard.recentOps.viewAll')} <ArrowRightOutlined style={{ fontSize: 'var(--text-xs)' }} />
              </a>
            }
            styles={{ body: { padding: 0 } }}
            style={{ height: '100%' }}
          >
            {!recentTx?.content?.length ? (
              <div style={{ padding: 18, color: 'var(--text-secondary)' }}>{t('dashboard.recentOps.empty')}</div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                {recentTx.content.slice(0, 5).map((tx: Transaction, i: number) => {
                  const inSym = tx.tokenInId ? symbolByTokenId.get(tx.tokenInId) : null
                  const outSym = tx.tokenOutId ? symbolByTokenId.get(tx.tokenOutId) : null
                  const pair = inSym && outSym ? `${inSym} → ${outSym}` : (tx.poolId ? pairByPoolId.get(tx.poolId) : null)
                  return (
                    <div
                      key={tx.id}
                      onClick={() => navigate('/transactions')}
                      {...rowButtonProps(() => navigate('/transactions'), t('dashboard.recentOps.openAria'))}
                      style={{
                        padding: '10px 16px',
                        borderTop: i === 0 ? 'none' : '1px solid var(--border-light)',
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        cursor: 'pointer',
                      }}
                    >
                      <div style={{ minWidth: 0, flex: 1 }}>
                        <div style={{ fontSize: 'var(--text-sm)', fontWeight: 500 }}>
                          {t(`dashboard.txType.${tx.txType}`, { defaultValue: tx.txType })}
                          {pair && (
                            <Text type="secondary" style={{ fontSize: 'var(--text-xs)', marginLeft: 6 }}>
                              {pair}
                            </Text>
                          )}
                        </div>
                        <div style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', fontFamily: 'JetBrains Mono, monospace' }}>
                          {dayjs(tx.createdAt).format('DD.MM HH:mm')}
                        </div>
                      </div>
                      <Tag
                        color={tx.status === 'CONFIRMED' ? 'success' : tx.status === 'FAILED' ? 'error' : 'processing'}
                        style={{ borderRadius: 'var(--radius-pill)', marginInlineEnd: 0, padding: '0 8px' }}
                      >
                        {t(`dashboard.txStatus.${tx.status}`, { defaultValue: tx.status })}
                      </Tag>
                    </div>
                  )
                })}
              </div>
            )}
          </Card>
        </Col>
      </Row>

      {/* Token balances */}
      <Card className="sber-card" title={<Text strong>{t('dashboard.tokens.title')}</Text>}
        extra={<Button type="link" onClick={() => navigate('/swap')}>{t('dashboard.myTokens.swap')} <ArrowRightOutlined /></Button>}>
        <Table
          scroll={{ x: 'max-content' }}
          className="sber-table"
          dataSource={[...(myBalances || [])].sort(
            (a: TokenBalance, b: TokenBalance) =>
              (b.available + b.locked) * (rubPriceBySymbol.get(b.symbol) ?? 0) -
              (a.available + a.locked) * (rubPriceBySymbol.get(a.symbol) ?? 0),
          )}
          rowKey="tokenId"
          pagination={false}
          size="middle"
          columns={[
            {
              title: t('dashboard.tokens.symbol'),
              dataIndex: 'symbol',
              render: (sym: string) => (
                <Space>
                  <TokenIcon symbol={sym} size={32} />
                  <Text strong>{sym}</Text>
                </Space>
              ),
            },
            {
              title: t('dashboard.tokens.available'),
              dataIndex: 'available',
              align: 'right' as const,
              render: (v: number) => v.toLocaleString('ru-RU', { maximumFractionDigits: 4 }),
            },
            {
              title: t('dashboard.tokens.locked'),
              dataIndex: 'locked',
              align: 'right' as const,
              render: (v: number) => v > 0 ? <Text type="warning">{v.toLocaleString('ru-RU', { maximumFractionDigits: 4 })}</Text> : '—',
            },
            {
              title: t('dashboard.tokens.price'),
              key: 'price',
              align: 'right' as const,
              render: (_: unknown, row: TokenBalance) => {
                const price = rubPriceBySymbol.get(row.symbol)
                if (price == null) return <Text type="secondary">—</Text>
                return (
                  <span style={{ fontVariantNumeric: 'tabular-nums' }}>
                    {price.toLocaleString('ru-RU', {
                      maximumFractionDigits: price >= 100 ? 2 : 4,
                    })} ₽
                  </span>
                )
              },
            },
            {
              title: t('dashboard.tokens.value'),
              key: 'value',
              align: 'right' as const,
              render: (_: unknown, row: TokenBalance) => {
                const price = rubPriceBySymbol.get(row.symbol)
                if (price == null) return <Text type="secondary">—</Text>
                const val = (row.available + row.locked) * price
                return <Text strong style={{ fontVariantNumeric: 'tabular-nums' }}>{formatRub(val)}</Text>
              },
            },
          ]}
        />
      </Card>

      {/* Active positions */}
      {activePositions.length > 0 && (
        <Card className="sber-card" title={<Text strong>{t('dashboard.activePositionsCard.title')}</Text>}
          extra={<Button type="link" onClick={() => navigate('/positions')}>{t('dashboard.activePositionsCard.viewAll')} <ArrowRightOutlined /></Button>}>
          <Table
            scroll={{ x: 'max-content' }}
            className="sber-table"
            dataSource={activePositions.slice(0, 3)}
            rowKey="id"
            pagination={false}
            size="middle"
            columns={[
              {
                title: t('dashboard.activePositionsCard.pool'),
                key: 'pool',
                render: (_: unknown, r: Position) => {
                  // Sprint 9 — Position DTO has no tokenXSymbol/tokenYSymbol,
                  // join via poolById (same fix as PositionsPage). Renders
                  // "SBER/SRUB" instead of "/" or "undefined/undefined".
                  const pool = poolById.get(r.poolId)
                  if (pool) return <Text strong>{pool.tokenXSymbol}/{pool.tokenYSymbol}</Text>
                  return <Text type="secondary">{t('positions.table.poolFallback', { id: r.poolId.slice(0, 6) })}</Text>
                },
              },
              {
                title: t('dashboard.activePositionsCard.strategy'),
                dataIndex: 'strategy',
                render: (s: string) => <Tag color="blue">{s}</Tag>,
              },
              {
                title: t('dashboard.activePositionsCard.priceRange'),
                key: 'range',
                render: (_: unknown, r: Position) => {
                  const pool = poolById.get(r.poolId)
                  if (!pool || !pool.activeBinId || !pool.binStep || !pool.currentPrice) {
                    return `${r.binRangeMin} — ${r.binRangeMax}`
                  }
                  const ratio = 1 + pool.binStep / 10000
                  const lo = pool.currentPrice * Math.pow(ratio, r.binRangeMin - pool.activeBinId)
                  const hi = pool.currentPrice * Math.pow(ratio, r.binRangeMax - pool.activeBinId)
                  const fmt = (n: number) =>
                    n.toLocaleString('ru-RU', { maximumFractionDigits: n >= 100 ? 2 : 4 })
                  return (
                    <span style={{ fontVariantNumeric: 'tabular-nums', fontSize: 'var(--text-sm)' }}>
                      {fmt(lo)} — {fmt(hi)} {pool.tokenYSymbol}
                    </span>
                  )
                },
              },
              {
                title: t('dashboard.activePositionsCard.unclaimedFees'),
                key: 'fees',
                align: 'right' as const,
                render: (_: unknown, r: Position) => {
                  const pool = poolById.get(r.poolId)
                  const xSym = pool?.tokenXSymbol ?? 'X'
                  const ySym = pool?.tokenYSymbol ?? 'Y'
                  if (r.unclaimedFeeX === 0 && r.unclaimedFeeY === 0) {
                    return <Text type="secondary">—</Text>
                  }
                  return (
                    <Space size={6} wrap style={{ justifyContent: 'flex-end' }}>
                      {r.unclaimedFeeX > 0 && (
                        <Tag color="green" style={{ marginInlineEnd: 0, borderRadius: 'var(--radius-pill)', padding: '0 8px' }}>
                          +{r.unclaimedFeeX.toLocaleString('ru-RU')} {xSym}
                        </Tag>
                      )}
                      {r.unclaimedFeeY > 0 && (
                        <Tag color="green" style={{ marginInlineEnd: 0, borderRadius: 'var(--radius-pill)', padding: '0 8px' }}>
                          +{r.unclaimedFeeY.toLocaleString('ru-RU')} {ySym}
                        </Tag>
                      )}
                    </Space>
                  )
                },
              },
            ]}
            onRow={(record) => ({
              style: { cursor: 'pointer' },
              onClick: () => navigate(`/pools/${record.poolId}`),
            })}
          />
        </Card>
      )}

      {/* Sprint 9 — bottom-of-page "Последние транзакции" full table
          removed. The split-row teaser above shows the last 5 ops and
          links to /transactions for the full paginated view with
          filters; two copies of the same data made the page feel
          cluttered. */}
    </Space>
  )
}
