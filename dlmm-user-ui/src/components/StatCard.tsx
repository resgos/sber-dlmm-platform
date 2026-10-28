import { Card } from 'antd'
import type { ReactNode } from 'react'

interface StatCardProps {
  title: string
  value: number | string
  icon: ReactNode
  iconBg: string
  iconColor: string
  formatter?: (value: number) => string
}

export default function StatCard({ title, value, icon, iconBg, iconColor, formatter }: StatCardProps) {
  const displayValue =
    typeof value === 'string'
      ? value
      : formatter
        ? formatter(value)
        : formatTokenAmount(value, null, { compact: false })

  return (
    <Card
      className="sber-card"
      hoverable
      style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-light)' }}
      styles={{ body: { padding: '20px' } }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14 }}>
        <div
          style={{
            width: 44,
            height: 44,
            borderRadius: 'var(--radius-sm)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 'var(--text-lg)',
            background: iconBg,
            color: iconColor,
            flexShrink: 0,
          }}
        >
          {icon}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)', fontWeight: 500, marginBottom: 'var(--space-1)' }}>{title}</div>
          <div style={{ fontSize: 24, fontWeight: 700, color: 'var(--text-primary)', lineHeight: 1.2 }}>{displayValue}</div>
        </div>
      </div>
    </Card>
  )
}

// formatRub used to live here with hardcoded Russian unit suffixes, so the EN
// UI showed Russian "billion" on Dashboard/Pools/Profile. The locale-aware
// version (K/M/B in EN) is in lib/format — re-exported so imports keep working.
// The import sits down here on purpose: lint:styles baselines by line number.
import { formatTokenAmount } from '@/lib/format'
export { formatRub } from '@/lib/format'
