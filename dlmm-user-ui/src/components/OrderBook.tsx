import { useMemo, useState } from 'react'
import { Card, Typography, Space, Tag, Empty, Spin, Tooltip, Segmented, Grid } from 'antd'
import {
  BookOutlined,
  CaretUpOutlined,
  CaretDownOutlined,
  HistoryOutlined,
  ArrowRightOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { pools as poolService, transactions } from '@/api/services'
import type { BinData, PoolDetail, Transaction } from '@/api/types'
import { formatCompact } from '@/lib/format'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/ru'

dayjs.extend(relativeTime)
dayjs.locale('ru')

const { Text } = Typography
const { useBreakpoint } = Grid

/**
 * OB-01 (FEATURE-BATCH-PLAN-2026-05-29) — order book / «стакан» built
 * entirely on OUR DLMM bin liquidity. No external market data.
 *
 * <p>KEY INSIGHT: in a DLMM the order book *is* the bin liquidity
 * distribution. `GET /pools/{id}` returns bins with {price, reserveX,
 * reserveY}. Per canonical DLMM (and our LiquidityService.assignSides):
 *   - bins BELOW the active bin hold only Y (reserveX==0) → BIDS
 *     (quote/SRUB waiting to buy X cheap),
 *   - bins ABOVE the active bin hold only X (reserveY==0) → ASKS
 *     (X waiting to be sold for Y),
 *   - the active bin is the mid (mixed reserves).
 *
 * <p>So the ladder shows ask size in X (sums ≈ pool.totalTvlX minus the
 * active bin's X) and bid size in Y (sums ≈ pool.totalTvlY minus the
 * active bin's Y) — directly checkable against the pool reserve
 * side-totals (see the dev-only totals footnote).
 *
 * <p>Design: T-01 data-viz tokens — asks `--viz-down`, bids `--viz-up`,
 * depth bars use the `-soft` fills. tabular-nums for every number. Row
 * click → onPickPrice(price). Both themes + mobile-collapsible via the
 * `compact` Segmented + AntD breakpoints. Zero hardcoded colours.
 */

interface OrderBookProps {
  poolId: string
  /**
   * Click a price level → bubble it up. PoolDetailPage uses this to
   * switch the action panel to «Обмен» and surface the picked level as
   * a reference. Optional — the ladder is fully usable read-only.
   */
  onPickPrice?: (price: number, side: 'bid' | 'ask') => void
  /** How many levels to show per side. Default 12. */
  depth?: number
}

interface Level {
  binId: number
  price: number
  /** Native-side size at this level: ask = reserveX, bid = reserveY. */
  size: number
  /** Unit symbol for `size` (xSym for asks, ySym for bids). */
  unit: string
  /** Running total from the spread outward. */
  cumulative: number
  side: 'bid' | 'ask'
}

/**
 * Split bins into ask levels (above active, nearest-first) and bid
 * levels (below active, nearest-first), each capped at `depth`. Sizes
 * are the native reserve on that side (asks → X, bids → Y), so summing
 * a full side reconciles against pool.totalTvlX / totalTvlY minus the
 * active bin's mixed reserves. Cumulative runs from the spread outward
 * so the depth bar grows away from the mid — the standard order-book
 * reading.
 */
function buildLadder(
  bins: BinData[],
  activeBinId: number,
  depth: number,
  xSym: string,
  ySym: string,
): { asks: Level[]; bids: Level[]; bestAsk?: Level; bestBid?: Level } {
  const above = bins
    .filter((b) => b.binId > activeBinId && b.reserveX > 0)
    .sort((a, b) => a.binId - b.binId)
    .slice(0, depth)
  const below = bins
    .filter((b) => b.binId < activeBinId && b.reserveY > 0)
    .sort((a, b) => b.binId - a.binId)
    .slice(0, depth)

  // Asks: cumulative from the spread (nearest ask) outward & upward.
  let askCum = 0
  const asksNearFirst: Level[] = above.map((b) => {
    askCum += b.reserveX
    return { binId: b.binId, price: b.price, size: b.reserveX, unit: xSym, cumulative: askCum, side: 'ask' as const }
  })

  // Bids: cumulative from the spread (nearest bid) outward & downward.
  let bidCum = 0
  const bids: Level[] = below.map((b) => {
    bidCum += b.reserveY
    return { binId: b.binId, price: b.price, size: b.reserveY, unit: ySym, cumulative: bidCum, side: 'bid' as const }
  })

  // Asks render top-to-bottom DESCENDING toward the spread, so the
  // nearest ask sits just above the mid-price chip. Reverse the
  // near-first list for display.
  const asks = [...asksNearFirst].reverse()

  return {
    asks,
    bids,
    bestAsk: asksNearFirst[0],
    bestBid: bids[0],
  }
}

export default function OrderBook({ poolId, onPickPrice, depth = 12 }: OrderBookProps) {
  const screens = useBreakpoint()
  const isMobile = !screens.md
  // Mobile: collapse to one section at a time to save vertical space.
  const [mobileView, setMobileView] = useState<'book' | 'trades'>('book')

  // Reuse the page's ['poolDetail', poolId] cache — the bin chart and
  // PoolDetailPage already populate it; this avoids a duplicate fetch
  // and keeps the ladder in lock-step with the chart's bins.
  const { data: pool, isLoading, error } = useQuery<PoolDetail>({
    queryKey: ['poolDetail', poolId],
    queryFn: () => poolService.getPool(poolId),
    refetchInterval: 10_000,
    enabled: !!poolId,
  })

  const ladder = useMemo(() => {
    if (!pool?.bins?.length) return null
    return buildLadder(
      pool.bins,
      pool.activeBinId,
      depth,
      pool.tokenXSymbol ?? 'X',
      pool.tokenYSymbol ?? 'Y',
    )
  }, [pool, depth])

  if (isLoading) {
    return (
      <Card
        className="sber-card sber-orderbook"
        title={<OrderBookTitle />}
        styles={{ body: { padding: 'var(--space-7) 0', textAlign: 'center' } }}
      >
        <Spin />
      </Card>
    )
  }

  if (error || !pool) {
    return (
      <Card className="sber-card sber-orderbook" title={<OrderBookTitle />}>
        <Empty description="Не удалось загрузить стакан" />
      </Card>
    )
  }

  const xSym = pool.tokenXSymbol
  const ySym = pool.tokenYSymbol
  const mid = pool.currentPrice

  // Spread from the two innermost levels; falls back to the active
  // price when one side is empty (single-sided liquidity).
  const bestAskPrice = ladder?.bestAsk?.price
  const bestBidPrice = ladder?.bestBid?.price
  const spreadAbs =
    bestAskPrice != null && bestBidPrice != null ? bestAskPrice - bestBidPrice : null
  const spreadPct = spreadAbs != null && mid > 0 ? (spreadAbs / mid) * 100 : null

  // Per-side depth-bar scale. Asks are sized in X, bids in Y — different
  // units with wildly different magnitudes on non-1.0 pairs (e.g. SBTC),
  // so a shared scale would flatten one side to invisibility. Each side
  // scales to its own deepest level (the outermost cumulative), which is
  // also the conventional exchange rendering.
  const maxAskCum = Math.max(ladder?.asks.reduce((m, l) => Math.max(m, l.cumulative), 0) ?? 0, 1)
  const maxBidCum = Math.max(ladder?.bids.reduce((m, l) => Math.max(m, l.cumulative), 0) ?? 0, 1)

  const hasLadder = !!ladder && (ladder.asks.length > 0 || ladder.bids.length > 0)

  // Reconciliation footnote — sum ALL above/below-active reserves (not
  // just the visible `depth` levels) so the totals can be eyeballed
  // against the pool's reserve side-totals. Ask depth (X) ≈ totalTvlX
  // minus the active bin's X; bid depth (Y) ≈ totalTvlY minus the
  // active bin's Y. (The active bin holds the mixed remainder, so the
  // ladder totals are the pool reserves net of the mid bin — exactly
  // what an order book should show.)
  const askDepthX = pool.bins
    .filter((b) => b.binId > pool.activeBinId)
    .reduce((s, b) => s + b.reserveX, 0)
  const bidDepthY = pool.bins
    .filter((b) => b.binId < pool.activeBinId)
    .reduce((s, b) => s + b.reserveY, 0)

  const book = (
    <div className="sber-orderbook__book">
      <LadderHeader xSym={xSym} ySym={ySym} />

      {/* Asks — above the spread, descending toward the mid. */}
      <div className="sber-orderbook__side" role="rowgroup" aria-label="Заявки на продажу">
        {!hasLadder || ladder!.asks.length === 0 ? (
          <div className="sber-orderbook__empty-side">нет заявок на продажу</div>
        ) : (
          ladder!.asks.map((l) => (
            <LadderRow key={`ask-${l.binId}`} level={l} maxCum={maxAskCum} onPickPrice={onPickPrice} />
          ))
        )}
      </div>

      {/* Mid-price + spread chip. */}
      <div className="sber-orderbook__mid">
        <Space size={6} align="center" wrap>
          <Text strong className="sber-orderbook__mid-price">
            {mid.toLocaleString('ru-RU', { maximumFractionDigits: 6 })}
          </Text>
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
            {ySym}/{xSym}
          </Text>
          {spreadPct != null && (
            <Tooltip
              title={
                spreadAbs != null
                  ? `Спред: ${spreadAbs.toLocaleString('ru-RU', { maximumFractionDigits: 6 })} ${ySym}`
                  : undefined
              }
            >
              <span className="sber-orderbook__spread">
                спред {spreadPct < 0.01 ? '< 0,01' : spreadPct.toFixed(2)}%
              </span>
            </Tooltip>
          )}
        </Space>
      </div>

      {/* Bids — below the spread, descending in price. */}
      <div className="sber-orderbook__side" role="rowgroup" aria-label="Заявки на покупку">
        {!hasLadder || ladder!.bids.length === 0 ? (
          <div className="sber-orderbook__empty-side">нет заявок на покупку</div>
        ) : (
          ladder!.bids.map((l) => (
            <LadderRow key={`bid-${l.binId}`} level={l} maxCum={maxBidCum} onPickPrice={onPickPrice} />
          ))
        )}
      </div>

      {/* Depth totals — reconcile against the pool's reserve side-totals
          so the ladder is visibly self-consistent. */}
      <div className="sber-orderbook__totals">
        <Tooltip title={`Сумма заявок на покупку (Y), резерв пула ${ySym}: ${formatCompact(pool.totalTvlY)}`}>
          <span>
            <span className="sber-orderbook__trade-buy">●</span> покупка{' '}
            {formatCompact(bidDepthY)} {ySym}
          </span>
        </Tooltip>
        <Tooltip title={`Сумма заявок на продажу (X), резерв пула ${xSym}: ${formatCompact(pool.totalTvlX)}`}>
          <span>
            <span className="sber-orderbook__trade-sell">●</span> продажа{' '}
            {formatCompact(askDepthX)} {xSym}
          </span>
        </Tooltip>
      </div>
    </div>
  )

  const trades = <RecentTradesFeed pool={pool} />

  return (
    <Card
      className="sber-card sber-orderbook"
      style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-light)' }}
      title={<OrderBookTitle />}
      extra={
        <Tooltip title="Синтезирован из ликвидности по бинам — это и есть стакан DLMM">
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
            из бинов пула
          </Text>
        </Tooltip>
      }
      styles={{ body: { padding: 0 } }}
    >
      {isMobile ? (
        <>
          <div style={{ padding: 'var(--space-3) var(--space-4) 0' }}>
            <Segmented
              block
              size="small"
              value={mobileView}
              onChange={(v) => setMobileView(v as 'book' | 'trades')}
              options={[
                { label: 'Стакан', value: 'book', icon: <BookOutlined /> },
                { label: 'Сделки', value: 'trades', icon: <HistoryOutlined /> },
              ]}
            />
          </div>
          {mobileView === 'book' ? book : trades}
        </>
      ) : (
        <>
          {book}
          <div className="sber-orderbook__divider" />
          {trades}
        </>
      )}
    </Card>
  )
}

function OrderBookTitle() {
  return (
    <Space size={8}>
      <BookOutlined style={{ color: 'var(--brand-primary)' }} />
      <Text strong>Стакан</Text>
    </Space>
  )
}

function LadderHeader({ xSym, ySym }: { xSym: string; ySym: string }) {
  return (
    <div className="sber-orderbook__row sber-orderbook__row--head" role="row">
      <span className="sber-orderbook__col sber-orderbook__col--price">Цена ({ySym})</span>
      <span className="sber-orderbook__col sber-orderbook__col--size">Объём ({xSym}/{ySym})</span>
      <span className="sber-orderbook__col sber-orderbook__col--cum">Накопл.</span>
    </div>
  )
}

function LadderRow({
  level,
  maxCum,
  onPickPrice,
}: {
  level: Level
  maxCum: number
  onPickPrice?: (price: number, side: 'bid' | 'ask') => void
}) {
  const isAsk = level.side === 'ask'
  const depthPct = Math.min(100, (level.cumulative / maxCum) * 100)
  const clickable = !!onPickPrice
  const Caret = isAsk ? CaretUpOutlined : CaretDownOutlined

  return (
    <div
      className={`sber-orderbook__row ${
        isAsk ? 'sber-orderbook__row--ask' : 'sber-orderbook__row--bid'
      }${clickable ? ' sber-orderbook__row--clickable' : ''}`}
      role={clickable ? 'button' : 'row'}
      tabIndex={clickable ? 0 : undefined}
      onClick={clickable ? () => onPickPrice!(level.price, level.side) : undefined}
      onKeyDown={
        clickable
          ? (e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                onPickPrice!(level.price, level.side)
              }
            }
          : undefined
      }
      title={clickable ? `Подставить цену ${level.price.toLocaleString('ru-RU', { maximumFractionDigits: 6 })}` : undefined}
    >
      {/* Depth bar — grows from the spread side, proportional to
          cumulative size, using the soft viz fill. Asks fill from the
          right, bids from the left, mirroring exchange convention. */}
      <span
        className="sber-orderbook__depth"
        style={{ width: `${depthPct}%` }}
        aria-hidden="true"
      />
      <span className="sber-orderbook__col sber-orderbook__col--price">
        <Caret className="sber-orderbook__caret" />
        {level.price.toLocaleString('ru-RU', { maximumFractionDigits: 6 })}
      </span>
      <Tooltip title={`${level.size.toLocaleString('ru-RU', { maximumFractionDigits: 4 })} ${level.unit}`}>
        <span className="sber-orderbook__col sber-orderbook__col--size">
          {formatCompact(level.size)}
        </span>
      </Tooltip>
      <Tooltip title={`Накоплено: ${level.cumulative.toLocaleString('ru-RU', { maximumFractionDigits: 4 })} ${level.unit}`}>
        <span className="sber-orderbook__col sber-orderbook__col--cum">
          {formatCompact(level.cumulative)}
        </span>
      </Tooltip>
    </div>
  )
}

/**
 * "Последние сделки" — recent SWAP transactions for THIS pool. Reuses
 * the pool-scoped feed endpoint (already filtered to txType=SWAP on the
 * backend). Side is derived from direction: tokenIn == tokenX means the
 * trader sold X for Y → a SELL (hits the bid); tokenIn == tokenY means
 * they bought X with Y → a BUY (lifts the ask).
 */
function RecentTradesFeed({ pool }: { pool: PoolDetail }) {
  const { data, isLoading } = useQuery<Transaction[]>({
    queryKey: ['poolRecentSwaps', pool.id, 20],
    queryFn: () => transactions.getRecentPoolTransactions(pool.id, 20),
    refetchInterval: 12_000,
    staleTime: 8_000,
  })

  // Defensive: the endpoint is pool-scoped + SWAP-only, but guard in
  // case it ever returns mixed types.
  const swaps = useMemo(
    () => (data ?? []).filter((t) => t.txType === 'SWAP'),
    [data],
  )

  return (
    <div className="sber-orderbook__trades">
      <div className="sber-orderbook__trades-head">
        <Space size={8}>
          <HistoryOutlined style={{ color: 'var(--text-secondary)' }} />
          <Text strong style={{ fontSize: 'var(--text-sm)' }}>
            Последние сделки
          </Text>
          {swaps.length > 0 && (
            <Tag style={{ borderRadius: 'var(--radius-pill)', fontSize: 'var(--text-xs)', margin: 0 }}>
              {swaps.length}
            </Tag>
          )}
        </Space>
      </div>

      {isLoading ? (
        <div style={{ padding: 'var(--space-5) 0', textAlign: 'center' }}>
          <Spin size="small" />
        </div>
      ) : swaps.length === 0 ? (
        <Empty description="Пока нет сделок" imageStyle={{ height: 40 }} style={{ padding: 'var(--space-5) 0' }} />
      ) : (
        <div className="sber-orderbook__trades-list">
          <div className="sber-orderbook__trade-row sber-orderbook__trade-row--head" role="row">
            <span className="sber-orderbook__trade-col sber-orderbook__trade-col--time">Время</span>
            <span className="sber-orderbook__trade-col sber-orderbook__trade-col--side">Сторона</span>
            <span className="sber-orderbook__trade-col sber-orderbook__trade-col--vol">Объём</span>
          </div>
          {swaps.map((t) => (
            <TradeRow key={t.id} tx={t} pool={pool} />
          ))}
        </div>
      )}
    </div>
  )
}

function TradeRow({ tx, pool }: { tx: Transaction; pool: PoolDetail }) {
  // tokenInId == tokenX → trader gave X, got Y → SELL X (hits a bid).
  // tokenInId == tokenY → trader gave Y, got X → BUY  X (lifts an ask).
  const soldX = tx.tokenInId === pool.tokenXId
  const isBuy = !soldX
  const sideLabel = isBuy ? 'Покупка' : 'Продажа'
  const sideClass = isBuy ? 'sber-orderbook__trade-buy' : 'sber-orderbook__trade-sell'
  const Caret = isBuy ? CaretUpOutlined : CaretDownOutlined

  // Volume expressed in X (the base asset) so the column is comparable
  // across buys and sells: a buy receives X (amountOut), a sell spends
  // X (amountIn).
  const xVol = isBuy ? tx.amountOut ?? 0 : tx.amountIn ?? 0
  const inSym = soldX ? pool.tokenXSymbol : pool.tokenYSymbol
  const outSym = soldX ? pool.tokenYSymbol : pool.tokenXSymbol

  const when = tx.createdAt ? dayjs(tx.createdAt) : null
  const whenAbs = when ? when.format('DD.MM.YYYY HH:mm:ss') : '—'
  const whenRel = when ? when.fromNow() : '—'

  return (
    <div className="sber-orderbook__trade-row" role="row">
      <Tooltip title={whenAbs}>
        <span className="sber-orderbook__trade-col sber-orderbook__trade-col--time">{whenRel}</span>
      </Tooltip>
      <span className={`sber-orderbook__trade-col sber-orderbook__trade-col--side ${sideClass}`}>
        <Caret className="sber-orderbook__caret" />
        {sideLabel}
      </span>
      <Tooltip
        title={`${formatCompact(tx.amountIn ?? 0)} ${inSym} → ${formatCompact(tx.amountOut ?? 0)} ${outSym}`}
      >
        <span className={`sber-orderbook__trade-col sber-orderbook__trade-col--vol ${sideClass}`}>
          {formatCompact(xVol)} {pool.tokenXSymbol}
          <ArrowRightOutlined className="sber-orderbook__trade-arrow" />
        </span>
      </Tooltip>
    </div>
  )
}
