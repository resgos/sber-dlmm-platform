import { useEffect, useRef } from 'react'
import { Card, Typography, Space, Empty, Spin, Tag } from 'antd'
import { LineChartOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import {
  createChart,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts'
import { oracle } from '@/api/services'
import type { OhlcvCandle } from '@/api/types'

const { Text } = Typography

/**
 * Sprint 9-DS-r4 (P1-4) — Meteora-style price/volume chart powered
 * by {@link https://github.com/tradingview/lightweight-charts} (free,
 * ~50KB gzipped). Reads OHLCV candles from the price-oracle endpoint
 * added in P1-11.
 *
 * <p>The chart is intentionally minimal:
 *   - One candlestick series (1-minute candles)
 *   - One volume histogram pinned to the bottom 20% of the pane
 *   - No axis labels, no crosshair tooltip beyond defaults
 *
 * <p>For pools with no swap history yet, renders an Empty state.
 * Refetches every 30s — matches the aggregator's flush cadence so
 * the chart catches new candles within roughly half a flush cycle.
 */
interface PoolPriceChartProps {
  poolId: string
  quoteSymbol: string
}

export default function PoolPriceChart({ poolId, quoteSymbol }: PoolPriceChartProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const volumeSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null)

  const { data, isLoading } = useQuery<OhlcvCandle[]>({
    queryKey: ['ohlcv', poolId, 60, 200],
    queryFn: () => oracle.getOhlcv(poolId, 60, 200),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    staleTime: 15_000,
  })

  // Initialise the chart once when the container element mounts.
  useEffect(() => {
    if (!containerRef.current) return
    const chart = createChart(containerRef.current, {
      autoSize: true,
      layout: {
        textColor: '#6B7280',
        background: { color: 'transparent' },
        fontFamily: 'SB Sans Text, Inter, sans-serif',
      },
      grid: {
        vertLines: { color: '#F3F4F6' },
        horzLines: { color: '#F3F4F6' },
      },
      rightPriceScale: {
        borderColor: '#E5E7EB',
        scaleMargins: { top: 0.1, bottom: 0.25 },
      },
      timeScale: {
        borderColor: '#E5E7EB',
        timeVisible: true,
        secondsVisible: false,
      },
      // Disable scaling on touch so the chart doesn't fight with
      // the page scroll on mobile.
      handleScale: { mouseWheel: true, pinch: true, axisPressedMouseMove: true },
    })

    // Sprint 9-DS-r4 (P1-4) — lightweight-charts v4 uses the
    // legacy addCandlestickSeries / addHistogramSeries shorthand.
    // (v5 introduces addSeries(SeriesType, options); we'll migrate
    // when the v5 stable lands.)
    candleSeriesRef.current = chart.addCandlestickSeries({
      upColor: '#21A038',
      downColor: '#DC2626',
      borderUpColor: '#21A038',
      borderDownColor: '#DC2626',
      wickUpColor: '#21A038',
      wickDownColor: '#DC2626',
    })

    volumeSeriesRef.current = chart.addHistogramSeries({
      // Volume series pinned to the bottom 20% via priceScaleId='' + scaleMargins
      // — the lightweight-charts overlay pattern.
      priceScaleId: '',
      color: 'rgba(33,160,56,0.4)',
      priceFormat: { type: 'volume' },
    })
    // Apply the bottom-overlay scale margins to the volume series'
    // private scale (priceScaleId='').
    chart.priceScale('').applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    })

    chartRef.current = chart
    return () => {
      chart.remove()
      chartRef.current = null
      candleSeriesRef.current = null
      volumeSeriesRef.current = null
    }
  }, [])

  // Push fresh candles into the chart whenever the query refetches.
  useEffect(() => {
    if (!data || !candleSeriesRef.current || !volumeSeriesRef.current) return
    const candles = data.map((c) => ({
      time: (Date.parse(c.time + 'Z') / 1000) as UTCTimestamp,
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close,
    }))
    candleSeriesRef.current.setData(candles)
    const volumes = data.map((c) => ({
      time: (Date.parse(c.time + 'Z') / 1000) as UTCTimestamp,
      value: c.volume,
      color: c.close >= c.open ? 'rgba(33,160,56,0.35)' : 'rgba(220,38,38,0.35)',
    }))
    volumeSeriesRef.current.setData(volumes)
    chartRef.current?.timeScale().fitContent()
  }, [data])

  return (
    <Card
      className="sber-card"
      style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-light)' }}
      title={
        <Space size={8}>
          <LineChartOutlined style={{ color: 'var(--sber-green)' }} />
          <Text strong>Цена ({quoteSymbol} за единицу)</Text>
          <Tag style={{ borderRadius: 'var(--radius-pill)', fontSize: 'var(--text-xs)' }}>1m · OHLCV</Tag>
        </Space>
      }
      extra={
        data && data.length > 0 && (
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
            {data.length} свечей
          </Text>
        )
      }
    >
      {isLoading ? (
        <div style={{ textAlign: 'center', padding: 60 }}>
          <Spin tip="Загрузка свечей..." />
        </div>
      ) : !data || data.length === 0 ? (
        <Empty
          description="Пока нет свопов — свечи появятся после первой сделки"
          imageStyle={{ height: 48 }}
          style={{ padding: '40px 0' }}
        />
      ) : (
        <div ref={containerRef} style={{ width: '100%', height: 320 }} />
      )}
    </Card>
  )
}
