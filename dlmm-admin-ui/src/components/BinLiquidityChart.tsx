import { useQuery } from '@tanstack/react-query'
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
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

  const chartData: ChartDataPoint[] = pool.bins.map((bin) => ({
    binId: bin.binId,
    price: bin.price.toFixed(6),
    reserveX: Number(bin.reserveX.toFixed(4)),
    reserveY: Number(bin.reserveY.toFixed(4)),
    liquidity: Number(bin.liquidity.toFixed(4)),
  }))

  // Show max 50 bins centered around active bin
  const activeBinIndex = chartData.findIndex((d) => d.binId === pool.activeBinId)
  let displayData = chartData
  if (chartData.length > 50) {
    const start = Math.max(0, activeBinIndex - 25)
    const end = Math.min(chartData.length, activeBinIndex + 25)
    displayData = chartData.slice(start, end)
  }

  return (
    <div>
      <div style={{ marginBottom: 8, color: '#9CA3AF', fontSize: 12 }}>
        Автообновление каждые 10 секунд. Активный бин: {pool.activeBinId}
      </div>
      <ResponsiveContainer width="100%" height={320}>
        <BarChart
          data={displayData}
          margin={{ top: 10, right: 30, left: 0, bottom: 5 }}
        >
          <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#E5E7EB" />
          <XAxis
            dataKey="binId"
            tick={{ fontSize: 11, fill: '#6B7280' }}
            label={{ value: 'ID бина', position: 'insideBottom', offset: -2, fill: '#6B7280' }}
          />
          <YAxis
            tick={{ fontSize: 11, fill: '#6B7280' }}
            tickFormatter={(v) => {
              if (v >= 1000000) return `${(v / 1000000).toFixed(1)}M`
              if (v >= 1000) return `${(v / 1000).toFixed(1)}K`
              return String(v)
            }}
          />
          <Tooltip
            formatter={(value: number, name: string) => [
              value.toLocaleString('ru-RU', { maximumFractionDigits: 4 }),
              name === 'reserveX' ? 'Резерв X' : 'Резерв Y',
            ]}
            labelFormatter={(label) => `ID бина: ${label}`}
            contentStyle={{ borderRadius: 8, border: '1px solid #E5E7EB' }}
          />
          <Legend
            formatter={(value) => (value === 'reserveX' ? 'Резерв X' : 'Резерв Y')}
          />
          <ReferenceLine
            x={pool.activeBinId}
            stroke="#EF4444"
            strokeWidth={2}
            strokeDasharray="4 2"
            label={{ value: 'Активный', position: 'top', fill: '#EF4444', fontSize: 12 }}
          />
          <Bar dataKey="reserveX" stackId="a" fill="#21A038" name="reserveX" radius={[2, 2, 0, 0]} />
          <Bar dataKey="reserveY" stackId="a" fill="#F59E0B" name="reserveY" radius={[2, 2, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
