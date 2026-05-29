import { useMemo, useState } from 'react'
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { Card, Segmented } from 'antd'
import dayjs from 'dayjs'
import { formatRub } from '@/lib/format'
import { representativeSeries } from '@/lib/dashboardSeries'
import { DASH_VIZ } from '@/styles/palette'

/**
 * DS-02 — "TVL за период". 30-day (toggle 7/30/90) area chart of total
 * platform TVL with a dashed prior-period overlay + hover tooltip.
 *
 * Data honesty: the LAST point equals the real current TVL (from
 * /admin/dashboard). Earlier points + the prior-period line are a
 * representative seeded trend (no platform-wide time-series endpoint exists).
 * The card sub-title says the history is modelled.
 */
type Range = 7 | 30 | 90

interface Point {
  /** ISO date */
  date: string
  tvl: number
  prior: number
}

function buildSeries(currentTvl: number, days: Range): Point[] {
  // Amplitude/drift scale gently with the window so 90д looks like a longer
  // climb than 7д without ever being loud.
  const drift = days === 7 ? 0.02 : days === 30 ? 0.05 : 0.12
  const amplitude = days === 7 ? 0.015 : 0.025
  const cur = representativeSeries(currentTvl, {
    length: days,
    seedKey: `tvl-${days}`,
    amplitude,
    drift,
  })
  // Prior period: same shape shifted down ~8% so the overlay reads as "last
  // period was lower" without implying a measured value.
  const today = dayjs()
  return cur.map((v, i) => ({
    date: today.subtract(days - 1 - i, 'day').toISOString(),
    tvl: v,
    prior: v * (0.9 - Math.sin(i / 4) * 0.015),
  }))
}

function TvlTooltip({ active, payload, label }: any) {
  if (!active || !payload?.length) return null
  const p = payload.find((x: any) => x.dataKey === 'tvl') ?? payload[0]
  return (
    <div className="ds-tvl-tip">
      <b>{formatRub(p?.value ?? 0)}</b>
      <div className="ds-tvl-tip-date">{dayjs(label).format('DD.MM.YYYY')}</div>
    </div>
  )
}

export default function TvlAreaChart({ currentTvl }: { currentTvl: number }) {
  const [range, setRange] = useState<Range>(30)
  const data = useMemo(() => buildSeries(currentTvl, range), [currentTvl, range])

  return (
    <Card className="ds-card" styles={{ body: { padding: 0 } }}>
      <div className="ds-card-h">
        <div>
          <h3>TVL за период</h3>
          <div className="ds-sub">Общая ликвидность по всем пулам · ряд смоделирован</div>
        </div>
        <div className="ds-actions">
          <Segmented
            className="ds-seg"
            size="small"
            value={range}
            onChange={(v) => setRange(v as Range)}
            options={[
              { label: '7д', value: 7 },
              { label: '30д', value: 30 },
              { label: '90д', value: 90 },
            ]}
          />
        </div>
      </div>
      <div className="ds-legend">
        <span>
          <span className="ds-legend-swatch" style={{ background: DASH_VIZ.accent }} />
          TVL
        </span>
        <span>
          <span className="ds-legend-swatch" style={{ background: DASH_VIZ.prior }} />
          Прошлый период
        </span>
      </div>
      <div className="ds-chart-body">
        <ResponsiveContainer width="100%" height={236}>
          <ComposedChart data={data} margin={{ top: 8, right: 14, left: 4, bottom: 4 }}>
            <defs>
              <linearGradient id="ds-tvl-grad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={DASH_VIZ.accent} stopOpacity={0.22} />
                <stop offset="100%" stopColor={DASH_VIZ.accent} stopOpacity={0.01} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke={DASH_VIZ.grid} vertical={false} />
            <XAxis
              dataKey="date"
              tickFormatter={(d) => dayjs(d).format('DD.MM')}
              tick={{ fontSize: 10.5, fill: DASH_VIZ.axis }}
              axisLine={false}
              tickLine={false}
              minTickGap={36}
            />
            <YAxis
              tick={{ fontSize: 10.5, fill: DASH_VIZ.axis, fontFamily: 'JetBrains Mono, monospace' }}
              axisLine={false}
              tickLine={false}
              width={64}
              tickFormatter={(v) => formatRub(v)}
            />
            <Tooltip content={<TvlTooltip />} cursor={{ stroke: DASH_VIZ.tooltipBg, strokeOpacity: 0.3, strokeDasharray: '2 2' }} />
            <Line
              type="monotone"
              dataKey="prior"
              stroke={DASH_VIZ.prior}
              strokeWidth={1.2}
              strokeDasharray="3 3"
              dot={false}
              isAnimationActive={false}
            />
            <Area
              type="monotone"
              dataKey="tvl"
              stroke={DASH_VIZ.accent}
              strokeWidth={2}
              fill="url(#ds-tvl-grad)"
              dot={false}
              isAnimationActive={false}
            />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
    </Card>
  )
}
