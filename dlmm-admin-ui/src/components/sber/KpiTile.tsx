import { Card } from 'antd'
import type React from 'react'

/**
 * Sprint 9 (post-DS-handoff) — KPI tile component lifted from the OTC
 * desk Claude Design mockup. The OTC desk used it inline; this is the
 * shared version applied across all admin list pages (Transactions,
 * Pools, Suspicious, Users, Tokens).
 *
 * <p>Design (matches docs/design/otc-desk-claude-design-DSv1/):
 *  - 36×36 rounded square icon container in a neutral surface tint
 *  - uppercase 11px label with 0.04em tracking
 *  - 22px tabular-nums value, optional accent colour
 *  - 11px sub-text muted
 *  - 14×16 body padding, 12px gap between icon and content
 */
export interface KpiTileProps {
  label: string
  value: number | string
  sub?: string
  icon: React.ReactNode
  /** Optional accent for the value (use a status colour for "К расчёту" etc). */
  accent?: string
}

export default function KpiTile({ label, value, sub, icon, accent }: KpiTileProps) {
  return (
    <Card
      className="sber-card"
      style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
      styles={{ body: { padding: '14px 16px' } }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
        <div
          style={{
            width: 36,
            height: 36,
            borderRadius: 10,
            background: 'var(--surface-1, #FAFAFA)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
            fontSize: 16,
          }}
          aria-hidden
        >
          {icon}
        </div>
        <div style={{ minWidth: 0, flex: 1 }}>
          <div
            style={{
              fontSize: 11,
              color: 'var(--text-secondary)',
              letterSpacing: '0.04em',
              textTransform: 'uppercase',
              fontWeight: 500,
              marginBottom: 4,
            }}
          >
            {label}
          </div>
          <div
            style={{
              fontSize: 22,
              fontWeight: 700,
              color: accent ?? 'var(--text-primary)',
              lineHeight: 1.1,
              fontVariantNumeric: 'tabular-nums',
            }}
          >
            {value}
          </div>
          {sub && (
            <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 3 }}>
              {sub}
            </div>
          )}
        </div>
      </div>
    </Card>
  )
}
