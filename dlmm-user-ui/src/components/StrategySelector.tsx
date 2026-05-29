import { Card, Typography, Row, Col } from 'antd'
import { AimOutlined, LineChartOutlined, ColumnWidthOutlined } from '@ant-design/icons'
import type { LiquidityStrategy } from '@/api/types'

const { Text } = Typography

interface StrategySelectorProps {
  value: LiquidityStrategy
  onChange: (strategy: LiquidityStrategy) => void
}

const strategies: Array<{
  key: LiquidityStrategy
  name: string
  description: string
  icon: React.ReactNode
}> = [
  {
    key: 'SPOT',
    name: 'Равномерная',
    description: 'Равномерное распределение ликвидности по диапазону. Подходит для стабильных пар.',
    icon: <ColumnWidthOutlined style={{ fontSize: 'var(--text-xl)' }} />,
  },
  {
    key: 'CURVE',
    name: 'Концентрированная',
    description: 'Ликвидность сконцентрирована вокруг текущей цены. Максимальная эффективность.',
    icon: <LineChartOutlined style={{ fontSize: 'var(--text-xl)' }} />,
  },
  {
    key: 'BID_ASK',
    name: 'Двусторонняя',
    description: 'Ликвидность размещена по обе стороны от цены. Стратегия маркет-мейкера.',
    icon: <AimOutlined style={{ fontSize: 'var(--text-xl)' }} />,
  },
]

export default function StrategySelector({ value, onChange }: StrategySelectorProps) {
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
            <Text strong style={{ display: 'block', marginBottom: 4 }}>{s.name}</Text>
            <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{s.description}</Text>
          </Card>
        </Col>
      ))}
    </Row>
  )
}
