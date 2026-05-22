import { Space, Typography } from 'antd'
import type React from 'react'

const { Title, Text } = Typography

/**
 * Sprint 9 (post-DS-handoff) — page header.
 *
 * <p>Replaces the inline `<div style={{display:'flex',justifyContent:'space-between'}}>`
 * + `<Title level={4}>` pattern repeated on every list page. Adds optional
 * subtitle, status pill, and right-side action slot.
 */
export interface PageHeaderProps {
  title: string
  /** Sub-title shown below the title in small grey type. */
  subtitle?: React.ReactNode
  /** Status pill rendered inline with the title (right of it). */
  status?: React.ReactNode
  /** Right-side action area: buttons, filters, etc. */
  actions?: React.ReactNode
}

export default function PageHeader({ title, subtitle, status, actions }: PageHeaderProps) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'flex-start',
        gap: 12,
        flexWrap: 'wrap',
      }}
    >
      <div style={{ minWidth: 0 }}>
        <Space size={10} align="center" wrap>
          <Title level={4} className="sber-page-title" style={{ margin: 0 }}>
            {title}
          </Title>
          {status}
        </Space>
        {subtitle && (
          <Text type="secondary" style={{ fontSize: 'var(--text-sm)', display: 'block', marginTop: 2 }}>
            {subtitle}
          </Text>
        )}
      </div>
      {actions && <div>{actions}</div>}
    </div>
  )
}
