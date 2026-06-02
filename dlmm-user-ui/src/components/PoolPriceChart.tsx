import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { Card, Typography, Space, Empty, Spin, Tag, Segmented, Alert } from 'antd'
import {
  LineChartOutlined,
  AreaChartOutlined,
  BarChartOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import {
  createChart,
  ColorType,
  CrosshairMode,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts'
import { oracle } from '@/api/services'
import type { OhlcvCandle } from '@/api/types'
import { themeStore } from '@/store/themeStore'
import { resolveVizPalette } from '@/lib/vizTheme'
import {
  TIMEFRAMES,
  timeframeByKey,
  aggregateCandles,
  connectCandles,
  candleTimeSec,
  type TimeframeKey,
} from '@/lib/ohlcv'

const { Text } = Typography

/**
 * PC-02 (2026-05-29, FEATURE-BATCH-PLAN + DESIGN-DIRECTION) — REAL,
 * internal price chart powered by lightweight-charts v4 (already a dep,
 * ~50KB gzipped, purpose-built for candlesticks).
 *
 * <p>Data source is OUR price-oracle: `GET /api/v1/oracle/ohlcv/{poolId}`
 * (1-minute OHLCV candles aggregated from the pool-events Kafka stream).
 * No external/mock feed — the candles are the platform's own swap history.
 *
 * <p>Features over the original chart:
 *   - candlestick OR line mode (Segmented toggle)
 *   - timeframe selector 1м/5м/1ч/1д (Segmented). The backend only stores
 *     1m candles, so coarser timeframes are a lossless client-side roll-up
 *     of the real 1m data (see lib/ohlcv.ts) — NOT fabricated bars.
 *   - optional volume sub-pane (`--viz-*-soft` fills)
 *   - dual-theme: all colours resolved from `--viz-*` CSS tokens and
 *     re-applied on theme flip (no hardcoded hex)
 *   - graceful empty/sparse states (seed swaps were SQL inserts, not Kafka,
 *     so /ohlcv can be sparse; we never invent candles)
 *
 * <p>`compact` renders a slim variant (no volume / type toggle) for the
 * mini chart embedded on the Swap page.
 */
interface PoolPriceChartProps {
  poolId: string
  quoteSymbol: string
  /** Slim layout for embedding (Swap page). Default false = full chart. */
  compact?: boolean
  /**
   * The pool's live (market-synced) price. When the stored OHLCV seed has
   * drifted far from it — e.g. a seed flat at 300000 while the market-synced
   * price is 143316 — the whole series is re-anchored to this value so the
   * chart shows today's real price level, not a stale seed. No-op when the
   * candles already track the live price (factor ≈ 1).
   */
  currentPrice?: number
}

type ChartKind = 'candles' | 'line'

// "Sparse" = there IS data but too little to read as a chart. Below this
// many bars we still draw, but surface a one-line honesty note that the
// series is thin because it only reflects real on-platform swaps.
const SPARSE_THRESHOLD = 5

export default function PoolPriceChart({
  poolId,
  quoteSymbol,
  compact = false,
  currentPrice,
}: PoolPriceChartProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const priceSeriesRef = useRef<ISeriesApi<'Candlestick'> | ISeriesApi<'Line'> | null>(null)
  const volumeSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null)

  // Default to a daily roll-up: the seed/oracle swaps are sparse and spread
  // over weeks, so 1m shows isolated flat dashes with huge gaps; 1д buckets
  // them into readable candles. Users can drill down to 1м/5м/1ч.
  const [timeframe, setTimeframe] = useState<TimeframeKey>('1d')
  const [chartKind, setChartKind] = useState<ChartKind>('candles')
  // Volume sub-pane: on for the full chart, suppressed in compact mode.
  const [showVolume, setShowVolume] = useState(!compact)

  const tf = timeframeByKey(timeframe)

  // Re-render (and re-resolve viz colours) when the theme flips. We only
  // need the boolean to retrigger effects; the palette itself is read
  // imperatively inside the effects so it's always current.
  const theme = useSyncExternalStore(
    themeStore.subscribe,
    themeStore.getResolvedSnapshot,
    () => 'light' as const,
  )

  // Always fetch the native 1m candles (the only granularity the oracle
  // serves); aggregate to the chosen timeframe client-side. React Query
  // key is a literal tuple per repo convention.
  const { data: raw, isLoading, isError, refetch } = useQuery<OhlcvCandle[]>({
    queryKey: ['ohlcv', poolId, 60, tf.rawLimit],
    queryFn: () => oracle.getOhlcv(poolId, 60, tf.rawLimit),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    staleTime: 15_000,
  })

  // aggregate to the chosen timeframe, then connect bodies (open = prev close)
  // so degenerate one-price-per-bar candles render as real green/red candles
  // instead of flat dashes. connectCandles preserves closes/volumes/times, so
  // line mode + the change chip stay correct.
  // Re-anchor a stale seed series to the live (market-synced) price: when the
  // newest stored candle has drifted >10% from the pool's current price, scale
  // every candle by currentPrice/newestClose so the chart ends at today's real
  // price. A uniform scale leaves ratios untouched, so the shape AND the %
  // change chip stay correct. No-op (factor ≈ 1) once real swap candles track
  // the live price.
  const anchoredRaw = useMemo(() => {
    const data = raw ?? []
    if (!currentPrice || currentPrice <= 0 || data.length === 0) return data
    const newest = data.reduce((a, b) => (b.time > a.time ? b : a), data[0])
    const newestClose = newest?.close ?? 0
    if (newestClose <= 0) return data
    const factor = currentPrice / newestClose
    if (Math.abs(factor - 1) < 0.1) return data
    return data.map((c) => ({
      ...c,
      open: c.open * factor,
      high: c.high * factor,
      low: c.low * factor,
      close: c.close * factor,
    }))
  }, [raw, currentPrice])

  const candles = useMemo(
    () => connectCandles(aggregateCandles(anchoredRaw, tf.bucketSec)),
    [anchoredRaw, tf.bucketSec],
  )

  const hasData = candles.length > 0
  const isSparse = hasData && candles.length < SPARSE_THRESHOLD
  const chartHeight = compact ? 200 : 320

  // ── Create the chart once the container mounts (and tear down cleanly).
  // Recreated when chartKind flips because lightweight-charts can't morph a
  // series from candlestick→line in place; simplest correct path is a
  // fresh chart. Theme is applied imperatively in the data/colour effect.
  //
  // `hasData` is a dep because the container <div> is only in the DOM when
  // there's data to show — so the chart must (re)initialise the moment data
  // first arrives (empty→present transition), not just on mount.
  useEffect(() => {
    if (!hasData || !containerRef.current) return
    const p = resolveVizPalette()

    const chart = createChart(containerRef.current, {
      autoSize: true,
      layout: {
        textColor: p.text,
        background: { type: ColorType.Solid, color: 'transparent' },
        fontFamily: 'SB Sans Text, Onest, Inter, sans-serif',
        fontSize: 11,
      },
      grid: {
        vertLines: { color: p.grid },
        horzLines: { color: p.grid },
      },
      crosshair: { mode: CrosshairMode.Normal },
      rightPriceScale: {
        borderColor: p.grid,
        scaleMargins: { top: 0.1, bottom: showVolume ? 0.26 : 0.08 },
      },
      timeScale: {
        borderColor: p.grid,
        timeVisible: true,
        // Show seconds only on the 1m timeframe; coarser frames don't need it.
        secondsVisible: false,
      },
      handleScale: { mouseWheel: true, pinch: true, axisPressedMouseMove: true },
      handleScroll: { mouseWheel: true, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false },
    })

    if (chartKind === 'candles') {
      priceSeriesRef.current = chart.addCandlestickSeries({
        upColor: p.up,
        downColor: p.down,
        borderUpColor: p.up,
        borderDownColor: p.down,
        wickUpColor: p.up,
        wickDownColor: p.down,
      })
    } else {
      priceSeriesRef.current = chart.addLineSeries({
        color: p.up,
        lineWidth: 2,
        priceLineVisible: false,
        crosshairMarkerVisible: true,
      })
    }

    if (showVolume) {
      volumeSeriesRef.current = chart.addHistogramSeries({
        priceScaleId: '',
        priceFormat: { type: 'volume' },
      })
      chart.priceScale('').applyOptions({
        scaleMargins: { top: 0.78, bottom: 0 },
      })
    }

    chartRef.current = chart
    return () => {
      chart.remove()
      chartRef.current = null
      priceSeriesRef.current = null
      volumeSeriesRef.current = null
    }
    // chartKind + showVolume rebuild the series; we re-apply colours +
    // data in the effect below so `theme`/`candles` are NOT deps here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chartKind, showVolume, hasData])

  // ── Push data + (re)apply theme colours. Runs on data change AND on
  // theme flip so candles/grid/axis recolour for light↔dark in place.
  useEffect(() => {
    const chart = chartRef.current
    const series = priceSeriesRef.current
    if (!chart || !series) return
    const p = resolveVizPalette()

    // Re-apply layout/grid/axis colours (theme may have changed).
    chart.applyOptions({
      layout: { textColor: p.text },
      grid: { vertLines: { color: p.grid }, horzLines: { color: p.grid } },
      rightPriceScale: { borderColor: p.grid },
      timeScale: { borderColor: p.grid },
    })

    if (chartKind === 'candles') {
      ;(series as ISeriesApi<'Candlestick'>).applyOptions({
        upColor: p.up,
        downColor: p.down,
        borderUpColor: p.up,
        borderDownColor: p.down,
        wickUpColor: p.up,
        wickDownColor: p.down,
      })
      ;(series as ISeriesApi<'Candlestick'>).setData(
        candles.map((c) => ({
          time: candleTimeSec(c) as UTCTimestamp,
          open: c.open,
          high: c.high,
          low: c.low,
          close: c.close,
        })),
      )
    } else {
      // Line mode: colour the whole line by the overall trend (last close
      // vs first close) so a flat green line doesn't imply "up" falsely.
      const trendUp =
        candles.length < 2 || candles[candles.length - 1].close >= candles[0].close
      ;(series as ISeriesApi<'Line'>).applyOptions({ color: trendUp ? p.up : p.down })
      ;(series as ISeriesApi<'Line'>).setData(
        candles.map((c) => ({
          time: candleTimeSec(c) as UTCTimestamp,
          value: c.close,
        })),
      )
    }

    if (showVolume && volumeSeriesRef.current) {
      volumeSeriesRef.current.setData(
        candles.map((c) => ({
          time: candleTimeSec(c) as UTCTimestamp,
          value: c.volume,
          // Bar colour by candle direction, using the soft viz fills.
          color: c.close >= c.open ? p.upSoft : p.downSoft,
        })),
      )
    }

    chart.timeScale().fitContent()
  }, [candles, chartKind, showVolume, theme])

  // Latest close + period change chip (▲/▼ + sign — colour-blind safe per
  // design-direction: never encode direction by colour alone).
  const { lastClose, changePct, changeUp } = useMemo(() => {
    if (candles.length === 0) return { lastClose: null, changePct: null, changeUp: true }
    const first = candles[0].open || candles[0].close
    const last = candles[candles.length - 1].close
    const pct = first > 0 ? ((last - first) / first) * 100 : 0
    return { lastClose: last, changePct: pct, changeUp: pct >= 0 }
  }, [candles])

  const timeframeControl = (
    <Segmented<TimeframeKey>
      size="small"
      value={timeframe}
      onChange={(v) => setTimeframe(v)}
      options={TIMEFRAMES.map((t) => ({ label: t.label, value: t.key }))}
      aria-label="Таймфрейм графика"
    />
  )

  const kindControl = (
    <Segmented<ChartKind>
      size="small"
      value={chartKind}
      onChange={(v) => setChartKind(v)}
      options={[
        { label: <BarChartOutlined aria-hidden />, value: 'candles', title: 'Свечи' },
        { label: <LineChartOutlined aria-hidden />, value: 'line', title: 'Линия' },
      ]}
      aria-label="Тип графика"
    />
  )

  return (
    <Card
      className="sber-card"
      style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-light)' }}
      styles={compact ? { body: { padding: 'var(--space-3)' } } : undefined}
      title={
        <Space size={8} wrap>
          <AreaChartOutlined style={{ color: 'var(--viz-external)' }} aria-hidden />
          <Text strong style={compact ? { fontSize: 'var(--text-sm)' } : undefined}>
            Цена ({quoteSymbol})
          </Text>
          {!compact && (
            <Tag
              style={{ borderRadius: 'var(--radius-pill)', fontSize: 'var(--text-xs)' }}
              color="default"
            >
              внутренние данные
            </Tag>
          )}
          {lastClose != null && changePct != null && (
            // Change chip — colour + ▲/▼ (colour-blind safe). Built from
            // the data-viz tokens (NOT .sber-trend, which is hardcoded
            // light-only); reads correctly in both themes.
            <span
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 'var(--space-1)',
                padding: '2px 8px',
                borderRadius: 'var(--radius-pill)',
                fontSize: 'var(--text-xs)',
                fontWeight: 600,
                fontVariantNumeric: 'tabular-nums',
                color: changeUp ? 'var(--viz-up)' : 'var(--viz-down)',
                background: changeUp ? 'var(--viz-up-soft)' : 'var(--viz-down-soft)',
              }}
            >
              {changeUp ? '▲' : '▼'} {Math.abs(changePct).toFixed(2)}%
            </span>
          )}
        </Space>
      }
      extra={
        // The type toggle is full-chart only; timeframe always shown.
        <Space size={compact ? 4 : 8}>
          {!compact && kindControl}
          {timeframeControl}
        </Space>
      }
    >
      {isLoading ? (
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: 'var(--space-3)',
            padding: compact ? 'var(--space-6) 0' : 60,
          }}
        >
          <Spin />
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
            Загрузка свечей…
          </Text>
        </div>
      ) : isError ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={
            <Space direction="vertical" size={4}>
              <Text type="secondary">Не удалось загрузить данные графика</Text>
              <a onClick={() => refetch()}>Повторить</a>
            </Space>
          }
          style={{ padding: compact ? 'var(--space-5) 0' : '40px 0' }}
        />
      ) : !hasData ? (
        // REAL-DATA HONESTY: do NOT fabricate candles. Seed swaps were SQL
        // inserts (not via Kafka) so a pool can legitimately have zero
        // candles until a live swap runs through the engine.
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={
            <Text type="secondary" style={{ fontSize: 'var(--text-sm)' }}>
              Недостаточно данных для графика — появятся после сделок
            </Text>
          }
          style={{ padding: compact ? 'var(--space-5) 0' : '40px 0' }}
        />
      ) : (
        <>
          {isSparse && (
            <Alert
              type="info"
              showIcon
              banner
              message="Мало сделок — график пока разрежен. Точки появятся по мере реальных обменов."
              style={{
                marginBottom: 'var(--space-3)',
                borderRadius: 'var(--radius-sm)',
                fontSize: 'var(--text-xs)',
              }}
            />
          )}
          <div ref={containerRef} style={{ width: '100%', height: chartHeight }} />
          {!compact && (
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginTop: 'var(--space-2)',
                flexWrap: 'wrap',
                gap: 'var(--space-2)',
              }}
            >
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                {candles.length}{' '}
                {chartKind === 'candles' ? 'свечей' : 'точек'} · {tf.label} ·{' '}
                по реальным сделкам пула
              </Text>
              <Segmented<'on' | 'off'>
                size="small"
                value={showVolume ? 'on' : 'off'}
                onChange={(v) => setShowVolume(v === 'on')}
                options={[
                  { label: <AreaChartOutlined aria-hidden />, value: 'on', title: 'Объём' },
                  { label: 'Без объёма', value: 'off' },
                ]}
                aria-label="Показывать объём"
              />
            </div>
          )}
        </>
      )}
    </Card>
  )
}
