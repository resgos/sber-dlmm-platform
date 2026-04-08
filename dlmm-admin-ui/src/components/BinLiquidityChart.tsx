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
import { Spin, Empty, Alert } from 'antd'
import { pools as poolService } from '@/api/services'

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

// Кастомный tooltip
const CustomTooltip = ({ active, payload, label }: any) => {
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
        Бин #{label}{d?.isActive ? ' ★ Активный' : ''}
      </div>
      <div style={{ color: '#6B7280' }}>Цена: {d?.price}</div>
      <div style={{ color: '#3B82F6' }}>
        Резерв Y: {Number(d?.reserveY ?? 0).toLocaleString('ru-RU', { maximumFractionDigits: 4 })}
      </div>
      <div style={{ color: '#21A038' }}>
        Резерв X: {Number(d?.reserveX ?? 0).toLocaleString('ru-RU', { maximumFractionDigits: 4 })}
      </div>
      <div style={{ color: '#9CA3AF', marginTop: 4 }}>
        Ликвидность: {Number(d?.liquidity ?? 0).toLocaleString('ru-RU')}
      </div>
    </div>
  )
}

export default function BinLiquidityChart({ poolId }: BinLiquidityChartProps) {
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

  // Берём 50 бинов вокруг активного
  let sliced = pool.bins
  if (pool.bins.length > 50) {
    const start = Math.max(0, effectiveActiveIdx - 25)
    const end = Math.min(pool.bins.length, effectiveActiveIdx + 25)
    sliced = pool.bins.slice(start, end)
  }

  const slicedActiveIdx = sliced.findIndex((b) => b.binId === pool.activeBinId)
  const maxLeft = slicedActiveIdx
  const maxRight = sliced.length - 1 - slicedActiveIdx

  const chartData: ChartDataPoint[] = sliced.map((bin, i) => {
    let side: 'left' | 'active' | 'right' = 'right'
    if (bin.binId === pool.activeBinId) side = 'active'
    else if (i < slicedActiveIdx) side = 'left'
    const distance = Math.abs(i - slicedActiveIdx)
    const maxDist = side === 'left' ? maxLeft : maxRight
    return {
      binId: bin.binId,
      price: bin.price.toFixed(2),
      reserveX: Number(bin.reserveX.toFixed(4)),
      reserveY: Number(bin.reserveY.toFixed(4)),
      liquidity: Number(bin.liquidity.toFixed(0)),
      isActive: bin.binId === pool.activeBinId,
      side,
      _distance: distance,
      _maxDist: maxDist,
    } as any
  })

  return (
    <div>
      {/* Легенда */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 20, marginBottom: 12, fontSize: 12, color: '#6B7280' }}>
        <span>
          <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 2, background: '#3B82F6', marginRight: 6, verticalAlign: 'middle' }} />
          Токен Y (ниже цены)
        </span>
        <span>
          <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 2, background: '#F59E0B', marginRight: 6, verticalAlign: 'middle' }} />
          Активный бин #{pool.activeBinId}
        </span>
        <span>
          <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 2, background: '#21A038', marginRight: 6, verticalAlign: 'middle' }} />
          Токен X (выше цены)
        </span>
        <span style={{ marginLeft: 'auto', color: '#9CA3AF' }}>
          Автообновление каждые 10 с
        </span>
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
            label={{ value: '← Токен Y   |   Активный   |   Токен X →', position: 'insideBottom', offset: -8, fill: '#9CA3AF', fontSize: 11 }}
          />
          <YAxis
            tick={{ fontSize: 11, fill: '#9CA3AF' }}
            axisLine={false}
            tickLine={false}
            tickFormatter={(v) => {
              if (v >= 1000000) return `${(v / 1000000).toFixed(1)}M`
              if (v >= 1000) return `${(v / 1000).toFixed(0)}K`
              return String(v)
            }}
          />
          <Tooltip content={<CustomTooltip />} cursor={{ fill: 'rgba(0,0,0,0.04)' }} />
          <ReferenceLine
            x={pool.activeBinId}
            stroke="#F59E0B"
            strokeWidth={2}
            strokeDasharray="5 3"
          />
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
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
