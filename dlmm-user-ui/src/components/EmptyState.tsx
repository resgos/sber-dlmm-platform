import { Empty, Space, Typography } from 'antd'
import type { ReactNode } from 'react'

const { Text } = Typography

interface EmptyStateProps {
  /** Headline — what's missing or why we're here. */
  title?: string
  /** Plain-language reason + nudge toward action. */
  description?: ReactNode
  /** Optional CTA — usually <Button>. */
  cta?: ReactNode
  /** Optional secondary action — usually <Button type="link">. */
  secondary?: ReactNode
  /** Compact mode — half padding, smaller icon. For inline empty (table rows etc). */
  size?: 'default' | 'compact'
}

/**
 * UI-CRITIQUE 2026-05-22 #9 — unified empty-state.
 *
 * Before: 3 inconsistent patterns across PoolsPage / PositionsPage /
 * PoolComparePage / TeamPage:
 *   1. `<Empty description="..." />` — default illustration
 *   2. `<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} ... />` — simple
 *   3. `<Alert type="info" message="..." />` — for "no results filter"
 *
 * After: one component, one pattern. Always SIMPLE illustration
 * (line drawing — lighter visual weight than AntD default illustration),
 * always optional title + description + cta + secondary.
 *
 * Future: swap `Empty.PRESENTED_IMAGE_SIMPLE` to custom SVG matching
 * Sber brand line style (Sprint 13).
 */
export default function EmptyState({
  title,
  description,
  cta,
  secondary,
  size = 'default',
}: EmptyStateProps) {
  return (
    <Empty
      image={Empty.PRESENTED_IMAGE_SIMPLE}
      // AntD 5.18+ deprecates imageStyle in favour of `styles` prop.
      styles={{ image: { height: size === 'compact' ? 40 : 60 } }}
      description={
        title || description ? (
          <Space direction="vertical" size={6} align="center" style={{ maxWidth: 360 }}>
            {title && <Text strong style={{ fontSize: 'var(--text-md)' }}>{title}</Text>}
            {description && (
              <Text type="secondary" style={{ fontSize: 'var(--text-sm)', textAlign: 'center' }}>
                {description}
              </Text>
            )}
          </Space>
        ) : null
      }
      style={{ padding: size === 'compact' ? 20 : 40 }}
    >
      {(cta || secondary) && (
        <Space size={12}>
          {cta}
          {secondary}
        </Space>
      )}
    </Empty>
  )
}
