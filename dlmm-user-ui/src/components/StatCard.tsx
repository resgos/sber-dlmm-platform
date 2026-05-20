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
        : value.toLocaleString('ru-RU')

  return (
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
        >
          {icon}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 13, color: '#6B7280', fontWeight: 500, marginBottom: 4 }}>{title}</div>
          <div style={{ fontSize: 24, fontWeight: 700, color: '#1F2937', lineHeight: 1.2 }}>{displayValue}</div>
        </div>
      </div>
    </Card>
  )
}

// Sprint 9 — added квадриллион + триллион steps. Seed data has
// inflated reserves (multi-trillion-rouble pools) and the previous
// version rendered them as "1810439.42 млрд ₽" — а wall of digits
// that nobody can read. квд/трлн caps keep the headline readable
// until the seed gets a proper realism pass (tracked separately).
export function formatRub(value: number): string {
  if (value >= 1_000_000_000_000_000) return `${(value / 1_000_000_000_000_000).toFixed(2)} квд ₽`
  if (value >= 1_000_000_000_000) return `${(value / 1_000_000_000_000).toFixed(2)} трлн ₽`
  if (value >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(2)} млрд ₽`
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(2)} млн ₽`
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)} тыс ₽`
  return `${value.toLocaleString('ru-RU')} ₽`
}
