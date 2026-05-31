import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  BarChart,
  Bar,
  Cell,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ReferenceLine,
  ResponsiveContainer,
} from 'recharts'
import { Spin, Empty, Alert, Button, Space, Tooltip as AntTooltip, Segmented } from 'antd'
import { ZoomInOutlined, ZoomOutOutlined, AimOutlined } from '@ant-design/icons'
import { pools as poolService } from '@/api/services'

// Sprint 9-DS-r4 (P2-9) — zoom levels parity with the user-side
// chart. ±25 is the default; admins on a wide monitor often zoom
// out to ±50 / ±100 to scan a whole pool, or in to ±5 / ±10 to
// debug a margin event.
const ZOOM_LEVELS = [5, 10, 25, 50, 100] as const
const DEFAULT_ZOOM_INDEX = 2

type ChartMode = 'liquidity' | 'reserves'

interface BinLiquidityChartProps {
  poolId: string
}

interface ChartDataPoint {
  binId: number
  price: string
  reserveX: number
  reserveY: number
  liquidity: number
  isActive: boolean
  side: 'left' | 'active' | 'right'
}

// Gradient: синий (Y-side) → золотой (active) → зелёный (X-side)
function getBinColor(side: 'left' | 'active' | 'right', distance: number, maxDist: number) {
  if (side === 'active') return '#F59E0B'
  const t = Math.min(distance / Math.max(maxDist, 1), 1)
  // Плавный градиент от центра к краям
  const opacity = Math.max(0.25, 1 - t * 0.6)
  if (side === 'left') {
    // Синий #3B82F6 → более насыщенный у активного
    const r = Math.round(59 + t * 20)
    const g = Math.round(130 - t * 40)
    const b = Math.round(246)
    return `rgba(${r},${g},${b},${opacity})`
  }
  // Зелёный #21A038 → более насыщенный у активного
  const r = Math.round(33)
  const g = Math.round(160 - t * 40)
  const b = Math.round(56)
  return `rgba(${r},${g},${b},${opacity})`
}

// Sprint 9 — tooltip uses real token symbols instead of opaque X/Y.
// Factory pattern: makeTooltip(xSym, ySym) returns the actual component
// closure so the chart can wire the active pool's symbols at render time.
const makeTooltip = (xSym: string, ySym: string) => ({ active, payload, label }: any) => {
  if (!active || !payload?.length) return null
  const d: ChartDataPoint = payload[0]?.payload
  return (
    <div style={{
      background: '#fff',
      border: '1px solid #E5E7EB',
      borderRadius: 8,
      padding: '10px 14px',
      fontSize: 12,
      boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
    }}>
      <div style={{ fontWeight: 600, marginBottom: 4, color: d?.isActive ? '#F59E0B' : '#111827' }}>
        {d?.isActive ? '★ Текущая цена' : 'Цена'}: {d?.price}
      </div>
      <div style={{ color: '#3B82F6' }}>
        Резерв {ySym}: {Number(d?.reserveY ?? 0).toLocaleString('ru-RU', { maximumFractionDigits: 4 })}
      </div>
      <div style={{ color: '#21A038' }}>
        Резерв {xSym}: {Number(d?.reserveX ?? 0).toLocaleString('ru-RU', { maximumFractionDigits: 4 })}
      </div>
      <div style={{ color: '#9CA3AF', marginTop: 4 }}>
        Ликвидность: {Number(d?.liquidity ?? 0).toLocaleString('ru-RU')}
      </div>
    </div>
  )
}

export default function BinLiquidityChart({ poolId }: BinLiquidityChartProps) {
  // Sprint 9-DS-r4 (P2-9) — parity with the user-side chart: zoom
  // controls + view mode toggle. The default ±25 window matches the
  // pre-r4 hard-coded slice, so admin habits don't break.
  const [zoomIndex, setZoomIndex] = useState<number>(DEFAULT_ZOOM_INDEX)
  const windowRadius = ZOOM_LEVELS[zoomIndex]
  const [mode, setMode] = useState<ChartMode>('liquidity')

  const { data: pool, isLoading, error } = useQuery({
    queryKey: ['poolDetail', poolId],
    queryFn: () => poolService.getPool(poolId),
    refetchInterval: 10000,
    enabled: !!poolId,
  })

  if (isLoading) {
    return (
      <div style={{ textAlign: 'center', padding: '40px 0' }}>
        <Spin tip="Загрузка данных бинов..." />
      </div>
    )
  }

  if (error) {
    return (
      <Alert
        message="Не удалось загрузить данные бинов"
        type="error"
        showIcon
        style={{ borderRadius: 8 }}
      />
    )
  }

  if (!pool?.bins || pool.bins.length === 0) {
    return <Empty description="Нет данных по бинам" />
  }

  const activeIdx = pool.bins.findIndex((b) => b.binId === pool.activeBinId)
  const effectiveActiveIdx = activeIdx === -1 ? Math.floor(pool.bins.length / 2) : activeIdx

  // Sprint 9-DS-r4 (P2-9) — slice driven by windowRadius (was a
  // hardcoded ±25). Parity with user-side chart's zoom controls.
  const startIdx = Math.max(0, effectiveActiveIdx - windowRadius)
  const endIdx = Math.min(pool.bins.length, effectiveActiveIdx + windowRadius + 1)
  const sliced = pool.bins.slice(startIdx, endIdx)

  const slicedActiveIdx = sliced.findIndex((b) => b.binId === pool.activeBinId)
  const maxLeft = slicedActiveIdx
  const maxRight = sliced.length - 1 - slicedActiveIdx

  const chartData: ChartDataPoint[] = sliced.map((bin, i) => {
    let side: 'left' | 'active' | 'right' = 'right'
    if (bin.binId === pool.activeBinId) side = 'active'
    else if (i < slicedActiveIdx) side = 'left'
    const distance = Math.abs(i - slicedActiveIdx)
    const maxDist = side === 'left' ? maxLeft : maxRight
    // Sprint 9-DS-r4 (P2-9) — stacked-reserves view normalises the X
    // side to Y-equivalent (× binPrice) so a single tower per bin
    // represents total depth in a single unit. The classic LB-DLMM
    // depth-chart shape: bins below active hold mostly Y; bins above
    // mostly X-in-Y-units. The math matches the user-side
    // BinLiquidityChart's choice of currentPrice as the conversion.
    const reserveXInY = Number((bin.reserveX * (pool.currentPrice ?? 1)).toFixed(4))
    return {
      binId: bin.binId,
      price: bin.price.toFixed(2),
      reserveX: Number(bin.reserveX.toFixed(4)),
      reserveY: Number(bin.reserveY.toFixed(4)),
      reserveXInY,
      liquidity: Number(bin.liquidity.toFixed(0)),
      isActive: bin.binId === pool.activeBinId,
      side,
      _distance: distance,
      _maxDist: maxDist,
    } as any
  })

  // Sprint 9 — tooltip closure with real token symbols.
  const xSym = pool.tokenXSymbol || 'токен X'
  const ySym = pool.tokenYSymbol || 'токен Y'
  const Tooltip2 = makeTooltip(xSym, ySym)

  return (
    <div>
      {/* Sprint 9-DS-r4 (P2-9) — legend + view-mode + zoom controls,
          parity with the user-side BinLiquidityChart. */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 16,
          marginBottom: 12,
          fontSize: 12,
          color: '#6B7280',
          flexWrap: 'wrap',
          justifyContent: 'space-between',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 20, flexWrap: 'wrap' }}>
          <span>
            <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 2, background: '#3B82F6', marginRight: 6, verticalAlign: 'middle' }} />
            Резерв {pool.tokenYSymbol || 'Y'} (ниже цены)
          </span>
          <span>
            <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 2, background: '#F59E0B', marginRight: 6, verticalAlign: 'middle' }} />
            Текущая цена{pool.currentPrice != null ? `: ${pool.currentPrice.toFixed(4)}` : ''}
          </span>
          <span>
            <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 2, background: '#21A038', marginRight: 6, verticalAlign: 'middle' }} />
            Резерв {pool.tokenXSymbol || 'X'} (выше цены)
          </span>
        </div>

        <Space size={8}>
          {/* Sprint 9-DS-r4 (P2-9) — Liquidity ⇄ Reserves view toggle.
              Reserves view stacks Y (blue) + X-in-Y (green) per bin —
              the canonical depth-chart shape. Liquidity view keeps the
              single-bar L-units shape that admins are used to. */}
          <Segmented
            size="small"
            value={mode}
            onChange={(v) => setMode(v as ChartMode)}
            options={[
              { label: 'Ликвидность', value: 'liquidity' },
              { label: 'Резервы (stacked)', value: 'reserves' },
            ]}
          />
          {/* Zoom controls — same shape as user-side BinLiquidityChart. */}
          <Space size={4}>
            <AntTooltip title="Приблизить">
              <Button
                size="small"
                type="text"
                aria-label="Приблизить"
                icon={<ZoomInOutlined />}
                disabled={zoomIndex === 0}
                onClick={() => setZoomIndex(Math.max(0, zoomIndex - 1))}
              />
            </AntTooltip>
            <AntTooltip title="К текущей цене">
              <Button
                size="small"
                type="text"
                aria-label="К текущей цене"
                icon={<AimOutlined />}
                onClick={() => setZoomIndex(DEFAULT_ZOOM_INDEX)}
              />
            </AntTooltip>
            <AntTooltip title="Отдалить">
              <Button
                size="small"
                type="text"
                aria-label="Отдалить"
                icon={<ZoomOutOutlined />}
                disabled={zoomIndex === ZOOM_LEVELS.length - 1}
                onClick={() => setZoomIndex(Math.min(ZOOM_LEVELS.length - 1, zoomIndex + 1))}
              />
            </AntTooltip>
            <span style={{ fontSize: 11, color: '#9CA3AF', minWidth: 48, textAlign: 'right' }}>
              ±{windowRadius}
            </span>
          </Space>
        </Space>
      </div>

      <ResponsiveContainer width="100%" height={320}>
        <BarChart
          data={chartData}
          margin={{ top: 10, right: 20, left: 0, bottom: 20 }}
          barCategoryGap="4%"
        >
          <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#F3F4F6" />
          <XAxis
            dataKey="binId"
            tick={false}
            axisLine={{ stroke: '#E5E7EB' }}
            label={{ value: '← ниже цены   |   текущая   |   выше цены →', position: 'insideBottom', offset: -8, fill: '#9CA3AF', fontSize: 11 }}
          />
          <YAxis
            tick={{ fontSize: 11, fill: '#9CA3AF' }}
            axisLine={false}
            tickLine={false}
            width={60}
            tickFormatter={(v) => {
              // Sprint 9-DS-r4 (P2-9, parity with P2-5 user-side fix)
              // — extend B/T prefixes; widen axis so the leading digit
              // doesn't get clipped on billion-scale TVL bins.
              const abs = Math.abs(v)
              if (abs >= 1e12) return `${(v / 1e12).toFixed(abs >= 1e14 ? 0 : 1)}T`
              if (abs >= 1e9) return `${(v / 1e9).toFixed(abs >= 1e11 ? 0 : 1)}B`
              if (abs >= 1e6) return `${(v / 1e6).toFixed(abs >= 1e8 ? 0 : 1)}M`
              if (abs >= 1e3) return `${(v / 1e3).toFixed(0)}K`
              return String(v)
            }}
          />
          <Tooltip content={Tooltip2} cursor={{ fill: 'rgba(0,0,0,0.04)' }} />
          <ReferenceLine
            x={pool.activeBinId}
            stroke="#F59E0B"
            strokeWidth={2}
            strokeDasharray="5 3"
          />
          {/* Sprint 9-DS-r4 (P2-9) — view toggle. Reserves view stacks
              Y (blue, bottom) + X-in-Y (green, top) per bin via the
              shared stackId, giving the canonical LB-DLMM depth-chart
              shape. Liquidity view is the legacy single-bar render. */}
          {mode === 'reserves' ? (
            <>
              <Bar dataKey="reserveY" stackId="reserves" fill="#3B82F6" radius={[0, 0, 0, 0]} maxBarSize={18} />
              <Bar dataKey="reserveXInY" stackId="reserves" fill="#21A038" radius={[3, 3, 0, 0]} maxBarSize={18} />
            </>
          ) : (
            <Bar dataKey="liquidity" radius={[3, 3, 0, 0]} maxBarSize={18}>
              {chartData.map((entry, index) => {
                const d = entry as any
                const color = getBinColor(entry.side, d._distance, d._maxDist)
                return (
                  <Cell
                    key={`cell-${index}`}
                    fill={color}
                    stroke={entry.isActive ? '#D97706' : 'none'}
                    strokeWidth={entry.isActive ? 2 : 0}
                  />
                )
              })}
            </Bar>
          )}
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
