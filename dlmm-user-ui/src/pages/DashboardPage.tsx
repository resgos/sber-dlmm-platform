import { useMemo } from 'react'
import { Row, Col, Card, Table, Tag, Space, Typography, Spin, Alert, Button } from 'antd'
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
import { balances, pools, fees, transactions, oracle, tokens as tokensApi } from '@/api/services'
import StatCard, { formatRub } from '@/components/StatCard'
import SpasiboWidget from '@/components/SpasiboWidget'
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

const txTypeLabels: Record<string, { text: string; color: string }> = {
  SWAP: { text: 'Обмен', color: 'blue' },
  ADD_LIQUIDITY: { text: 'Добавление', color: 'green' },
  REMOVE_LIQUIDITY: { text: 'Удаление', color: 'orange' },
  CLAIM_FEE: { text: 'Комиссии', color: 'gold' },
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

export default function DashboardPage() {
  const navigate = useNavigate()

  const { data: myBalances, isLoading: loadingBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })

  const { data: myPositions } = useQuery({
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

  const activePositions = (myPositions || []).filter((p: Position) => p.isActive)

  if (loadingBalances) {
    return <div style={{ textAlign: 'center', padding: '80px 0' }}><Spin size="large" /></div>
  }

  const totalEarned = (feeSummary?.totalClaimed ?? 0) + (feeSummary?.totalUnclaimed ?? 0)
  const earnedDelta = totalBalanceRub > 0 ? (totalEarned / totalBalanceRub) * 100 : 0

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      {/* Hero — gradient lockup with the headline portfolio number */}
      <div className="sber-hero">
        <Row gutter={[24, 16]} align="middle">
          <Col xs={24} md={14}>
            <div className="sber-hero-title">Ваш портфель</div>
            <div className="sber-hero-value">{formatRub(totalBalanceRub)}</div>
            <div className="sber-hero-meta" style={{ marginTop: 6 }}>
              {activePositions.length} активн{activePositions.length === 1 ? 'ая' : 'ых'} позици
              {activePositions.length === 1 ? 'я' : 'й'} · доход {formatRub(totalEarned)}
              {earnedDelta > 0 && ` (+${earnedDelta.toFixed(2)}%)`}
            </div>
          </Col>
          <Col xs={24} md={10} style={{ textAlign: 'right' }}>
            <Space size={12} wrap>
              <Button size="large" onClick={() => navigate('/swap')}
                style={{ background: 'rgba(255,255,255,0.18)', borderColor: 'rgba(255,255,255,0.35)', color: 'var(--bg-card)' }}>
                Обменять
              </Button>
              <Button size="large" onClick={() => navigate('/pools')}
                style={{ background: 'var(--bg-card)', borderColor: 'var(--bg-card)', color: 'var(--sber-green-dark)', fontWeight: 600 }}>
                В пулы <ArrowRightOutlined />
              </Button>
            </Space>
          </Col>
        </Row>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="Общий баланс" value={totalBalanceRub} icon={<WalletOutlined />}
            iconBg={DASHBOARD_TILE_PALETTE.balance.bg}
            iconColor={DASHBOARD_TILE_PALETTE.balance.fg}
            formatter={formatRub} />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="Активные позиции" value={activePositions.length} icon={<PieChartOutlined />}
            iconBg={DASHBOARD_TILE_PALETTE.positions.bg}
            iconColor={DASHBOARD_TILE_PALETTE.positions.fg} />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="Незабранные комиссии" value={feeSummary?.totalUnclaimed ?? 0} icon={<DollarOutlined />}
            iconBg={DASHBOARD_TILE_PALETTE.feesPending.bg}
            iconColor={DASHBOARD_TILE_PALETTE.feesPending.fg}
            formatter={formatRub} />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="Всего заработано" value={feeSummary?.totalClaimed ?? 0} icon={<TrophyOutlined />}
            iconBg={DASHBOARD_TILE_PALETTE.earned.bg}
            iconColor={DASHBOARD_TILE_PALETTE.earned.fg}
            formatter={formatRub} />
        </Col>
      </Row>

      {/* Sprint 5 #5.5 — SberSpasibo conversion widget. Promo card */}
      {/* placement so the loyalty path is the first thing the user sees */}
      {/* after the stat tiles. */}
      <Row gutter={[16, 16]}>
        <Col xs={24} md={10} lg={8}>
          <SpasiboWidget />
        </Col>
      </Row>

      {/* Token balances */}
      <Card className="sber-card" title={<Text strong>Мои токены</Text>}
        extra={<Button type="link" onClick={() => navigate('/swap')}>Обменять <ArrowRightOutlined /></Button>}>
        <Table
          className="sber-table"
          dataSource={myBalances || []}
          rowKey="tokenId"
          pagination={false}
          size="middle"
          columns={[
            {
              title: 'Токен',
              dataIndex: 'symbol',
              render: (sym: string) => (
                <Space>
                  <div style={{
                    width: 32, height: 32, borderRadius: 16,
                    background: 'var(--sber-green-light)', display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontWeight: 700, fontSize: 12, color: 'var(--sber-green)',
                  }}>
                    {sym?.slice(0, 2)}
                  </div>
                  <Text strong>{sym}</Text>
                </Space>
              ),
            },
            {
              title: 'Доступно',
              dataIndex: 'available',
              align: 'right' as const,
              render: (v: number) => v.toLocaleString('ru-RU', { maximumFractionDigits: 4 }),
            },
            {
              title: 'Заблокировано',
              dataIndex: 'locked',
              align: 'right' as const,
              render: (v: number) => v > 0 ? <Text type="warning">{v.toLocaleString('ru-RU', { maximumFractionDigits: 4 })}</Text> : '—',
            },
            {
              title: 'Цена',
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
              title: 'Стоимость',
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
        <Card className="sber-card" title={<Text strong>Активные позиции</Text>}
          extra={<Button type="link" onClick={() => navigate('/positions')}>Все позиции <ArrowRightOutlined /></Button>}>
          <Table
            className="sber-table"
            dataSource={activePositions.slice(0, 3)}
            rowKey="id"
            pagination={false}
            size="middle"
            columns={[
              {
                title: 'Пул',
                key: 'pool',
                render: (_: unknown, r: Position) => {
                  // Sprint 9 — Position DTO has no tokenXSymbol/tokenYSymbol,
                  // join via poolById (same fix as PositionsPage). Renders
                  // "SBER/SRUB" instead of "/" or "undefined/undefined".
                  const pool = poolById.get(r.poolId)
                  if (pool) return <Text strong>{pool.tokenXSymbol}/{pool.tokenYSymbol}</Text>
                  return <Text type="secondary">пул {r.poolId.slice(0, 6)}…</Text>
                },
              },
              {
                title: 'Стратегия',
                dataIndex: 'strategy',
                render: (s: string) => <Tag color="blue">{s}</Tag>,
              },
              {
                title: 'Диапазон цен',
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
                    <span style={{ fontVariantNumeric: 'tabular-nums', fontSize: 13 }}>
                      {fmt(lo)} — {fmt(hi)} {pool.tokenYSymbol}
                    </span>
                  )
                },
              },
              {
                title: 'Незабранные комиссии',
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
                        <Tag color="green" style={{ marginInlineEnd: 0, borderRadius: 999, padding: '0 8px' }}>
                          +{r.unclaimedFeeX.toLocaleString('ru-RU')} {xSym}
                        </Tag>
                      )}
                      {r.unclaimedFeeY > 0 && (
                        <Tag color="green" style={{ marginInlineEnd: 0, borderRadius: 999, padding: '0 8px' }}>
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

      {/* Recent transactions */}
      <Card className="sber-card" title={<Text strong>Последние транзакции</Text>}
        extra={<Button type="link" onClick={() => navigate('/transactions')}>Вся история <ArrowRightOutlined /></Button>}>
        {!recentTx?.content?.length ? (
          <Text type="secondary">Транзакций пока нет</Text>
        ) : (
          <Table
            className="sber-table"
            dataSource={recentTx.content}
            rowKey="id"
            pagination={false}
            size="middle"
            columns={[
              {
                title: 'Дата',
                dataIndex: 'createdAt',
                render: (d: string) => (
                  <span style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12 }}>
                    {dayjs(d).format('DD.MM.YYYY HH:mm')}
                  </span>
                ),
              },
              {
                title: 'Тип',
                dataIndex: 'txType',
                render: (t: string) => {
                  const cfg = txTypeLabels[t] || { text: t, color: 'default' }
                  return <Tag color={cfg.color}>{cfg.text}</Tag>
                },
              },
              {
                title: 'Пара',
                key: 'pair',
                render: (_: unknown, r: Transaction) => {
                  const inSym = r.tokenInId ? symbolByTokenId.get(r.tokenInId) : null
                  const outSym = r.tokenOutId ? symbolByTokenId.get(r.tokenOutId) : null
                  if (inSym && outSym) {
                    return (
                      <Space size={6}>
                        <Text strong style={{ fontSize: 13 }}>{inSym}</Text>
                        <SwapOutlined style={{ color: 'var(--text-muted, #9CA3AF)', fontSize: 11 }} />
                        <Text strong style={{ fontSize: 13 }}>{outSym}</Text>
                      </Space>
                    )
                  }
                  if (r.poolId) {
                    const pair = pairByPoolId.get(r.poolId)
                    if (pair) return <Text strong style={{ fontSize: 13 }}>{pair}</Text>
                  }
                  return <Text type="secondary">—</Text>
                },
              },
              {
                title: 'Сумма',
                key: 'amount',
                align: 'right' as const,
                render: (_: unknown, r: Transaction) => {
                  if (r.amountIn == null) return <Text type="secondary">—</Text>
                  const sym = r.tokenInId ? symbolByTokenId.get(r.tokenInId) : null
                  return (
                    <span style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                      {r.amountIn.toLocaleString('ru-RU')}
                      {sym && <Text type="secondary" style={{ fontSize: 11, marginLeft: 6 }}>{sym}</Text>}
                    </span>
                  )
                },
              },
              {
                title: 'Статус',
                dataIndex: 'status',
                render: (s: string) => {
                  const cfg = statusLabels[s] || { text: s, color: 'default' }
                  return <Tag color={cfg.color}>{cfg.text}</Tag>
                },
              },
            ]}
          />
        )}
      </Card>
    </Space>
  )
}
