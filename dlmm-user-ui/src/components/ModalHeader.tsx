import { Space, Typography } from 'antd'
import {
  InfoCircleFilled,
  CheckCircleFilled,
  WarningFilled,
  CloseCircleFilled,
} from '@ant-design/icons'
import type { ReactNode } from 'react'

const { Text } = Typography

type Severity = 'info' | 'success' | 'warning' | 'danger'

interface ModalHeaderProps {
  title: string
  severity?: Severity
  icon?: ReactNode
}

const SEVERITY_ICONS: Record<Severity, { icon: ReactNode; color: string }> = {
  info: { icon: <InfoCircleFilled />, color: 'var(--plasma-accent)' },
  success: { icon: <CheckCircleFilled />, color: 'var(--brand-primary)' },
  warning: { icon: <WarningFilled />, color: 'var(--sber-amber)' },
  danger: { icon: <CloseCircleFilled />, color: 'var(--color-negative)' },
}

/**
 * UI-CRITIQUE 2026-05-22 #12 — unified Modal header.
 *
 * Before: 3 inconsistent patterns в Modal titles across the codebase:
 *   - plain text ("Удаление ликвидности")
 *   - text + icon hand-built ("⚠️ Включение 2FA")
 *   - text + status Tag ("Новые резервные коды [warning]")
 *
 * After: pass `<ModalHeader title="..." severity="danger" />` as the
 * Modal `title` prop. User быстрее распознаёт "что я сейчас делаю —
 * это безобидная настройка или потенциально опасное действие?".
 *
 * Severity → icon + colour mapping:
 *   info     — InfoCircle  + Plasma blue
 *   success  — CheckCircle + brand green
 *   warning  — WarningFilled + Sber amber
 *   danger   — CloseCircle + colour-negative red
 *
 * Custom `icon` overrides the severity default (rare).
 */
export default function ModalHeader({ title, severity = 'info', icon }: ModalHeaderProps) {
  const def = SEVERITY_ICONS[severity]
  const iconNode = icon ?? def.icon
  return (
    <Space size={10} align="center">
      <span style={{ color: def.color, fontSize: 'var(--text-md)', display: 'inline-flex' }}>
        {iconNode}
      </span>
      <Text strong style={{ fontSize: 'var(--text-md)' }}>{title}</Text>
    </Space>
  )
}
