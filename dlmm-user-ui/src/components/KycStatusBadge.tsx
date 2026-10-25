import { Tag } from 'antd'
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  MinusCircleOutlined,
} from '@ant-design/icons'
import { useTranslation } from 'react-i18next'

const statusConfig: Record<string, { color: string; icon: React.ReactNode }> = {
  VERIFIED: { color: 'success', icon: <CheckCircleOutlined /> },
  PENDING: { color: 'warning', icon: <ClockCircleOutlined /> },
  REJECTED: { color: 'error', icon: <CloseCircleOutlined /> },
  NOT_SUBMITTED: { color: 'default', icon: <MinusCircleOutlined /> },
  BLOCKED: { color: 'error', icon: <CloseCircleOutlined /> },
}

export default function KycStatusBadge({ status, large }: { status: string; large?: boolean }) {
  const { t } = useTranslation()
  const key = statusConfig[status] ? status : 'NOT_SUBMITTED'
  const cfg = statusConfig[key]
  return (
    <Tag
      color={cfg.color}
      icon={cfg.icon}
      style={large ? { fontSize: 'var(--text-base)', padding: '4px 16px' } : undefined}
    >
      {t(`profile.kyc.status.${key}`)}
    </Tag>
  )
}
