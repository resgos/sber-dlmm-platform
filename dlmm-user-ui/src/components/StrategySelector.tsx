import { Card, Typography, Row, Col } from 'antd'
import { AimOutlined, LineChartOutlined, ColumnWidthOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import type { LiquidityStrategy } from '@/api/types'
import { strategyLabel } from '@/lib/strategy'

const { Text } = Typography

interface StrategySelectorProps {
  value: LiquidityStrategy
  onChange: (strategy: LiquidityStrategy) => void
}

// Display names + descriptions resolve through i18n (strategy.<S>.name/.desc)
// so the strategy picker is bilingual; the name uses the shared strategyLabel
// helper so it matches the term shown on position tags / the liquidity table.
const strategies: Array<{ key: LiquidityStrategy; icon: React.ReactNode }> = [
  { key: 'SPOT', icon: <ColumnWidthOutlined style={{ fontSize: 'var(--text-xl)' }} /> },
  { key: 'CURVE', icon: <LineChartOutlined style={{ fontSize: 'var(--text-xl)' }} /> },
  { key: 'BID_ASK', icon: <AimOutlined style={{ fontSize: 'var(--text-xl)' }} /> },
]

export default function StrategySelector({ value, onChange }: StrategySelectorProps) {
  const { t } = useTranslation()
  return (
    <Row gutter={[12, 12]}>
      {strategies.map((s) => (
        <Col xs={24} sm={8} key={s.key}>
          <Card
            className={`strategy-card ${value === s.key ? 'active' : ''}`}
            hoverable
            onClick={() => onChange(s.key)}
            styles={{ body: { padding: 16, textAlign: 'center' } }}
          >
            <div style={{ color: value === s.key ? 'var(--brand-primary)' : 'var(--text-secondary)', marginBottom: 'var(--space-2)' }}>
              {s.icon}
            </div>
            <Text strong style={{ display: 'block', marginBottom: 4 }}>{strategyLabel(s.key)}</Text>
            <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{t(`strategy.${s.key}.desc`)}</Text>
          </Card>
        </Col>
      ))}
    </Row>
  )
}
