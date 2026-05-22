import { Tag } from 'antd'
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  MinusCircleOutlined,
} from '@ant-design/icons'

const statusConfig: Record<string, { color: string; text: string; icon: React.ReactNode }> = {
  VERIFIED: { color: 'success', text: 'Верифицирован', icon: <CheckCircleOutlined /> },
  PENDING: { color: 'warning', text: 'Ожидание верификации', icon: <ClockCircleOutlined /> },
  REJECTED: { color: 'error', text: 'Отклонено', icon: <CloseCircleOutlined /> },
  NOT_SUBMITTED: { color: 'default', text: 'Не подана', icon: <MinusCircleOutlined /> },
  BLOCKED: { color: 'error', text: 'Заблокирован', icon: <CloseCircleOutlined /> },
}

export default function KycStatusBadge({ status, large }: { status: string; large?: boolean }) {
  const cfg = statusConfig[status] || statusConfig.NOT_SUBMITTED
  return (
    <Tag
      color={cfg.color}
      icon={cfg.icon}
      style={large ? { fontSize: 'var(--text-base)', padding: '4px 16px' } : undefined}
    >
      {cfg.text}
    </Tag>
  )
}
