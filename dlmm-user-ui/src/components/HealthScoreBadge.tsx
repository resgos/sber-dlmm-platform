import { Tooltip, Space, Typography, Progress, Popover, Button } from 'antd'
import { HeartFilled, InfoCircleOutlined, RetweetOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { Position, Pool } from '@/api/types'
import { calculateHealth, bandColor, type HealthFactor } from '@/lib/positionHealth'

const { Text } = Typography

interface Props {
  position: Position
  pool: Pool | undefined
  /** Small (table cell) or medium (detail card). */
  size?: 'small' | 'medium'
}

/**
 * Sprint 10 (new feature) — Position Health Score badge.
 * Sprint 10 wave 3 polish — readable on touch (click-to-open Popover
 * mirrors the hover Tooltip) + larger number + ring-style progress
 * indicator for better at-a-glance scanning.
 *
 * Single 0-100 number on each LP position row. Drives:
 *   - At-a-glance triage on PositionsPage ("which positions need
 *     attention?")
 *   - Anchor for future automation (auto-claim trigger, suggested
 *     rebalance candidate)
 *
 * Visual: number + heart icon + horizontal progress bar; colour ramp
 * via CSS vars (sber-green/amber/critical) so theme-switching works.
 */
export default function HealthScoreBadge({ position, pool, size = 'small' }: Props) {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const health = calculateHealth(position, pool)
  const color = bandColor(health.band)
  // Sprint 12 G-03 — actionable CTA. Если оценка плохая или so-so —
  // показываем кнопку «Ребалансировать» прямо в тултипе. До этого
  // tooltip объяснял проблему но не предлагал решение — Елена и
  // Anna обе спрашивали "что мне с этим делать".
  const needsAction = health.band === 'poor' || health.band === 'fair'
  // Sprint 10 wave 3 — bumped from 12 → 14 in small; 16 → 20 in medium.
  // The 12px badge was getting lost in dense table rows; reviewers
  // called it out as "tiny".
  const fontSize = size === 'small' ? 14 : 20
  const barWidth = size === 'small' ? 60 : 140

  const tooltipContent = (
    <Space direction="vertical" size={6} style={{ minWidth: 280 }}>
      <Text strong style={{ color: 'var(--bg-card)' }}>{t('positions.healthBadge.title', { total: health.total })}</Text>
      <FactorRow label={t('positions.healthBadge.factorRange')} weight="45%" factor={health.factors.rangeFit} />
      <FactorRow label={t('positions.healthBadge.factorFees')} weight="35%" factor={health.factors.feeEarning} />
      <FactorRow label={t('positions.healthBadge.factorAge')} weight="20%" factor={health.factors.age} />
      <Text style={{ color: 'var(--bg-card)', fontSize: 'var(--text-xs)', opacity: 0.75, display: 'block', marginTop: 4 }}>
        {t('positions.healthBadge.disclaimer')}
      </Text>
      {/* Sprint 12 G-03 — actionable CTA. */}
      {needsAction && (
        <Button
          size="small"
          type="primary"
          ghost
          icon={<RetweetOutlined />}
          onClick={(e) => {
            // Stop propagation so the popover click handler doesn't
            // re-toggle the popup before navigation completes.
            e.stopPropagation()
            navigate('/rebalance')
          }}
          style={{ marginTop: 4, alignSelf: 'flex-start' }}
        >
          {t('positions.healthBadge.rebalance')}
        </Button>
      )}
    </Space>
  )

  // Sprint 10 wave 3 — wrap both Tooltip + Popover so touch users
  // (no hover) can tap the badge to see the breakdown. AntD's
  // Popover and Tooltip stack cleanly via the click+hover trigger
  // pair; the Popover doesn't fire on hover so we don't get two
  // panels at once.
  const badge = (
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
      {/* Tiny ⓘ for touch users so the affordance is visible. */}
      <InfoCircleOutlined style={{ fontSize: size === 'small' ? 11 : 13, color: 'var(--text-muted)', marginInlineStart: 2 }} />
    </Space>
  )

  return (
    <Tooltip title={tooltipContent} placement="left" mouseEnterDelay={0.2}>
      <Popover content={tooltipContent} placement="left" trigger="click">
        {badge}
      </Popover>
    </Tooltip>
  )
}

function FactorRow({ label, weight, factor }: {
  label: string
  weight: string
  factor: HealthFactor
}) {
  const { t } = useTranslation()
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
        <Text style={{ color: 'var(--bg-card)', fontSize: 'var(--text-xs)', fontWeight: 500 }}>
          {label} <span style={{ opacity: 0.6 }}>({weight})</span>
        </Text>
        <Text style={{ color: 'var(--bg-card)', fontSize: 'var(--text-xs)', fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
          +{factor.contribution}
        </Text>
      </div>
      <Text style={{ color: 'var(--bg-card)', fontSize: 'var(--text-xs)', opacity: 0.75 }}>
        {t(`positions.healthBadge.reasons.${factor.reasonKey}`, factor.reasonParams)}
      </Text>
    </div>
  )
}
