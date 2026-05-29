import type { Delta } from '@/lib/dashboardSeries'
import { formatDeltaCount, formatDeltaPct } from '@/lib/dashboardSeries'
import { Tooltip } from 'antd'

/**
 * DS-02 — delta-vs-prior pill. Green up / red down / grey flat, ALWAYS paired
 * with a ▲/▼ glyph + sign so it's colour-blind safe (per DESIGN-DIRECTION:
 * "never encode up/down by colour alone").
 *
 * When the delta is representative (no real prior signal — `delta.real ===
 * false`) we wrap it in a tooltip that says so, so we never imply false
 * precision on the headline.
 */
export interface DeltaPillProps {
  delta: Delta
  /** 'pct' → "+1,8%", 'count' → "+12". */
  mode?: 'pct' | 'count'
}

function TriUp() {
  return (
    <svg viewBox="0 0 10 10" aria-hidden>
      <path d="M5 2.5L8 6H2z" fill="currentColor" />
    </svg>
  )
}
function TriDown() {
  return (
    <svg viewBox="0 0 10 10" aria-hidden>
      <path d="M5 7.5L2 4h6z" fill="currentColor" />
    </svg>
  )
}

export default function DeltaPill({ delta, mode = 'pct' }: DeltaPillProps) {
  const label = mode === 'count' ? formatDeltaCount(delta) : formatDeltaPct(delta)
  const cls = `ds-delta ${delta.direction}`
  const pill = (
    <span className={cls}>
      {delta.direction === 'up' && <TriUp />}
      {delta.direction === 'down' && <TriDown />}
      {label}
    </span>
  )
  if (!delta.real) {
    return (
      <Tooltip title="Оценочное изменение — точный исторический ряд недоступен">
        <span style={{ opacity: 0.92 }}>{pill}</span>
      </Tooltip>
    )
  }
  return pill
}
