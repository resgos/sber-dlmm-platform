import { Card } from 'antd'
import React from 'react'
import { Link } from 'react-router-dom'

/**
 * Sprint 7 dedup — extracted from inline definition in DashboardPage.tsx
 * (which was itself a copy of user-ui's components/StatCard.tsx).
 *
 * <p>Note on cross-app duplication: user-ui has the same component.
 * Full cross-app dedup would require monorepo workspaces (Sprint 9+
 * `dlmm-ui-common` package). For now both UIs maintain identical
 * local components — at least within-app the duplication is removed.
 */
export interface StatCardProps {
  title: string
  value: number
  icon: React.ReactNode
  iconBg: string
  iconColor: string
  formatter?: (value: number) => string
  /**
   * Sprint 9 #M-4 (UX-001) — drill-down link. When set, the entire
   * tile becomes a clickable router Link to {@code to}. Audit M-4
   * fix: dashboard tiles used to be inert; now they navigate to a
   * filtered list view per tile (e.g. "Verified" tile → /users?kycStatus=VERIFIED).
   */
  to?: string
}

export default function StatCard({
  title,
  value,
  icon,
  iconBg,
  iconColor,
  formatter,
  to,
}: StatCardProps) {
  const inner = (
    <Card
      className="sber-card"
      hoverable
      style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
      styles={{ body: { padding: '20px' } }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14 }}>
        <div
          style={{
            width: 44,
            height: 44,
            borderRadius: 10,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 20,
            background: iconBg,
            color: iconColor,
            flexShrink: 0,
          }}
          aria-hidden
        >
          {icon}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 13, color: '#6B7280', fontWeight: 500, marginBottom: 4 }}>
            {title}
          </div>
          <div style={{ fontSize: 24, fontWeight: 700, color: '#1F2937', lineHeight: 1.2 }}>
            {formatter ? formatter(value) : value.toLocaleString('ru-RU')}
          </div>
        </div>
      </div>
    </Card>
  )

  if (to) {
    return (
      <Link
        to={to}
        style={{ display: 'block', textDecoration: 'none', color: 'inherit' }}
        aria-label={`${title}: ${formatter ? formatter(value) : value.toLocaleString('ru-RU')} — открыть подробнее`}
      >
        {inner}
      </Link>
    )
  }
  return inner
}

/**
 * Russian-locale rouble formatter — used across stat tiles, dashboards,
 * report headings. Bucketed into B / M / k / raw for readability.
 */
export function formatRub(value: number): string {
  if (value >= 1_000_000_000) {
    return `${(value / 1_000_000_000).toFixed(2)} млрд ₽`
  }
  if (value >= 1_000_000) {
    return `${(value / 1_000_000).toFixed(2)} млн ₽`
  }
  if (value >= 1_000) {
    return `${(value / 1_000).toFixed(1)} тыс ₽`
  }
  return `${value.toLocaleString('ru-RU')} ₽`
}
