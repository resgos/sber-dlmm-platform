import { Card, Space, Typography, Tooltip } from 'antd'
import { useTranslation } from 'react-i18next'
import { PORTFOLIO_SEGMENT_COLORS } from '@/styles/palette'
import { formatRub } from '@/lib/format'
import type { PortfolioSegment } from '@/lib/portfolioStructure'

const { Text } = Typography

/**
 * 2026-06-17 — «Структура портфеля»: stacked composition bar + legend for the
 * hero total (top wallet tokens / прочие / LP capital / unclaimed fees). Pure
 * CSS flex — no recharts, so it renders (and screenshots) instantly. Slices
 * keep true proportions but get a 3px floor so a 0.01% slice stays visible as
 * a sliver instead of vanishing.
 */
export default function PortfolioStructure({ segments }: { segments: PortfolioSegment[] }) {
  const { t } = useTranslation()
  if (segments.length === 0) return null

  const color = (seg: PortfolioSegment, i: number): string => {
    if (seg.kind === 'token') {
      const cycle = PORTFOLIO_SEGMENT_COLORS.TOKEN_CYCLE
      return cycle[i % cycle.length]
    }
    return PORTFOLIO_SEGMENT_COLORS[seg.kind]
  }
  const label = (seg: PortfolioSegment): string =>
    seg.kind === 'token' ? (seg.symbol ?? '?') : t(`dashboard.structure.${seg.kind}`)
  // Sub-percent slices read as "0%" — show "<0,01%" instead (F-07 pattern).
  const pctText = (pct: number) => (pct > 0 && pct < 0.01 ? '<0,01%' : `${pct}%`)

  return (
    <Card className="sber-card" title={<Text strong>{t('dashboard.structure.title')}</Text>}>
      <div
        role="img"
        aria-label={t('dashboard.structure.title')}
        style={{ display: 'flex', width: '100%', height: 14, borderRadius: 'var(--radius-pill)', overflow: 'hidden' }}
      >
        {segments.map((seg, i) => (
          <Tooltip key={`${seg.kind}-${seg.symbol ?? ''}`} title={`${label(seg)}: ${formatRub(seg.value)} (${pctText(seg.pct)})`}>
            <div style={{ flex: `0 0 ${seg.pct}%`, minWidth: 3, background: color(seg, i) }} />
          </Tooltip>
        ))}
      </div>
      <Space wrap size={[16, 6]} style={{ marginTop: 12 }}>
        {segments.map((seg, i) => (
          <Space key={`${seg.kind}-${seg.symbol ?? ''}`} size={6}>
            <span style={{ display: 'inline-block', width: 10, height: 10, borderRadius: 2, background: color(seg, i) }} />
            <Text style={{ fontSize: 'var(--text-xs)' }}>{label(seg)}</Text>
            <Text type="secondary" style={{ fontSize: 'var(--text-xs)', fontVariantNumeric: 'tabular-nums' }}>
              {formatRub(seg.value)} · {pctText(seg.pct)}
            </Text>
          </Space>
        ))}
      </Space>
    </Card>
  )
}
