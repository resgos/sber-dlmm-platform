import { Area, AreaChart, ResponsiveContainer } from 'recharts'
import { useId } from 'react'
import { DASH_VIZ } from '@/styles/palette'

/**
 * DS-02 — tiny KPI sparkline. recharts Area with a soft gradient fill and a
 * dot on the last point, matching the mockup's inline-SVG sparks.
 *
 * `isAnimationActive={false}` per the project recharts perf rule. The series
 * is representative (see lib/dashboardSeries.ts) — purely decorative trend.
 */
export interface SparklineProps {
  data: number[]
  /** Stroke + gradient colour. Pass a resolved colour (CSS vars don't work
   *  inside recharts SVG gradients reliably), default = Sber green. */
  color?: string
  width?: number | string
  height?: number
}

export default function Sparkline({
  data,
  color = DASH_VIZ.accent,
  width = 78,
  height = 28,
}: SparklineProps) {
  const gradId = useId().replace(/:/g, '') // useId() returns ":r1:" — invalid in url(#…)
  const chartData = data.map((v, i) => ({ i, v }))
  return (
    <div style={{ width, height }} aria-hidden>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={chartData} margin={{ top: 2, right: 2, bottom: 2, left: 2 }}>
          <defs>
            <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={color} stopOpacity={0.22} />
              <stop offset="100%" stopColor={color} stopOpacity={0} />
            </linearGradient>
          </defs>
          <Area
            type="monotone"
            dataKey="v"
            stroke={color}
            strokeWidth={1.4}
            fill={`url(#${gradId})`}
            isAnimationActive={false}
            dot={false}
            activeDot={false}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}
