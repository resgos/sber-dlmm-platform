import { Tooltip, Space, Typography, Progress } from 'antd'
import { HeartFilled } from '@ant-design/icons'
import type { Position, Pool } from '@/api/types'
import { calculateHealth, bandColor } from '@/lib/positionHealth'

const { Text } = Typography

interface Props {
  position: Position
  pool: Pool | undefined
  /** Small (table cell) or medium (detail card). */
  size?: 'small' | 'medium'
}

/**
 * Sprint 10 (new feature) — Position Health Score badge.
 *
 * Single 0-100 number on each LP position row with a tooltip explaining
 * the three contributing factors. Drives:
 *   - At-a-glance triage on PositionsPage ("which positions need
 *     attention?")
 *   - Anchor for future automation (auto-claim trigger, suggested
 *     rebalance candidate)
 *
 * Visual: number + heart icon + horizontal progress bar; colour ramp
 * via CSS vars (sber-green/amber/critical) so theme-switching works.
 */
export default function HealthScoreBadge({ position, pool, size = 'small' }: Props) {
  const health = calculateHealth(position, pool)
  const color = bandColor(health.band)
  const fontSize = size === 'small' ? 12 : 16
  const barWidth = size === 'small' ? 60 : 140

  const tooltipContent = (
    <Space direction="vertical" size={6} style={{ minWidth: 280 }}>
      <Text strong style={{ color: 'var(--bg-card)' }}>Здоровье позиции: {health.total} / 100</Text>
      <FactorRow label="Соответствие диапазону" weight="45%" {...health.factors.rangeFit} />
      <FactorRow label="Доходность по комиссиям" weight="35%" {...health.factors.feeEarning} />
      <FactorRow label="Возраст позиции" weight="20%" {...health.factors.age} />
      <Text style={{ color: 'var(--bg-card)', fontSize: 11, opacity: 0.75, display: 'block', marginTop: 4 }}>
        Эвристический индикатор. Не является инвестиционной рекомендацией.
      </Text>
    </Space>
  )

  return (
    <Tooltip title={tooltipContent} placement="left">
      <Space size={6} align="center" style={{ cursor: 'help' }}>
        <HeartFilled style={{ color, fontSize }} />
        <Text strong style={{ fontSize, color, fontVariantNumeric: 'tabular-nums', minWidth: 28, textAlign: 'right' }}>
          {health.total}
        </Text>
        {size === 'medium' && (
          <Progress
            percent={health.total}
            size="small"
            showInfo={false}
            strokeColor={color}
            style={{ width: barWidth, marginInlineStart: 4 }}
          />
        )}
      </Space>
    </Tooltip>
  )
}

function FactorRow({ label, weight, contribution, reason }: {
  label: string
  weight: string
  contribution: number
  reason: string
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
        <Text style={{ color: 'var(--bg-card)', fontSize: 12, fontWeight: 500 }}>
          {label} <span style={{ opacity: 0.6, fontSize: 10 }}>({weight})</span>
        </Text>
        <Text style={{ color: 'var(--bg-card)', fontSize: 12, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
          +{contribution}
        </Text>
      </div>
      <Text style={{ color: 'var(--bg-card)', fontSize: 11, opacity: 0.75 }}>{reason}</Text>
    </div>
  )
}
