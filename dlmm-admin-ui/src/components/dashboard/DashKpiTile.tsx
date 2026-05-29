import type React from 'react'
import { Card } from 'antd'
import DeltaPill from './DeltaPill'
import Sparkline from './Sparkline'
import type { Delta } from '@/lib/dashboardSeries'
import { DASH_VIZ } from '@/styles/palette'

/**
 * DS-02 — KPI tile matching docs/design/admin-dashboard-claude-design `.kpi`.
 *
 * value + unit (tabular-nums) · delta pill · sparkline · foot note.
 * The value is real (from /admin/dashboard); the sparkline + delta are
 * representative trend (see lib/dashboardSeries.ts). `accent` for the spark
 * is resolved to a literal because recharts SVG gradients can't read CSS vars.
 */
export interface DashKpiTileProps {
  label: string
  /** Pre-formatted value WITHOUT the unit, e.g. "2,42". */
  value: string
  /** Small unit suffix, e.g. "млрд ₽". Omit for plain counts. */
  unit?: string
  delta: Delta
  /** 'pct' (default) or 'count' for the delta pill. */
  deltaMode?: 'pct' | 'count'
  sparkData: number[]
  /** Resolved spark colour (defaults to the delta's semantic colour). */
  sparkColor?: string
  /** Small note under the foot row. */
  foot?: React.ReactNode
}

export default function DashKpiTile({
  label,
  value,
  unit,
  delta,
  deltaMode = 'pct',
  sparkData,
  sparkColor,
  foot,
}: DashKpiTileProps) {
  const color = sparkColor ?? (delta.direction === 'down' ? DASH_VIZ.danger : DASH_VIZ.accent)
  return (
    <Card className="ds-card ds-kpi" styles={{ body: { padding: 0 } }}>
      <div className="ds-kpi-label">{label}</div>
      <div className="ds-kpi-value ds-num">
        {value}
        {unit && <span className="ds-unit">{unit}</span>}
      </div>
      <div className="ds-kpi-foot">
        <DeltaPill delta={delta} mode={deltaMode} />
        <Sparkline data={sparkData} color={color} />
      </div>
      {foot && <div className="ds-kpi-foot-note">{foot}</div>}
    </Card>
  )
}
