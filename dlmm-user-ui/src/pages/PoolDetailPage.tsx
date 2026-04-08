import { useParams, useNavigate } from 'react-router-dom'
import { Row, Col, Card, Typography, Space, Tag, Button, Descriptions, Spin, Alert } from 'antd'
import { ArrowLeftOutlined, PlusOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { pools } from '@/api/services'
import BinLiquidityChart from '@/components/BinLiquidityChart'
import StatCard, { formatRub } from '@/components/StatCard'
import {
  DollarOutlined,
  BarChartOutlined,
  PercentageOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'

const { Title, Text } = Typography

const statusColors: Record<string, string> = {
  ACTIVE: 'success',
  PAUSED: 'warning',
  SHUTDOWN: 'error',
  PENDING: 'processing',
}

export default function PoolDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const { data: pool, isLoading, error } = useQuery({
    queryKey: ['poolDetail', id],
    queryFn: () => pools.getPool(id!),
    enabled: !!id,
  })

  if (isLoading) return <div style={{ textAlign: 'center', padding: '80px 0' }}><Spin size="large" /></div>
  if (error || !pool) return <Alert message="Пул не найден" type="error" showIcon />

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Space>
          <Button icon={<ArrowLeftOutlined />} type="text" onClick={() => navigate('/pools')} />
          <Title level={4} className="sber-page-title" style={{ margin: 0 }}>
            {pool.tokenXSymbol}/{pool.tokenYSymbol}
          </Title>
          <Tag color={statusColors[pool.status]}>{pool.status}</Tag>
        </Space>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate(`/pools/${id}/liquidity`)}>
          Добавить ликвидность
        </Button>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="Текущая цена" value={pool.currentPrice.toLocaleString('ru-RU', { maximumFractionDigits: 4 })}
            icon={<DollarOutlined />} iconBg="#FEF3C7" iconColor="#F59E0B" />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="TVL" value={pool.totalTvlX + pool.totalTvlY}
            icon={<BarChartOutlined />} iconBg="#E8F5E9" iconColor="#21A038" formatter={formatRub} />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="Объём 24ч" value={pool.volume24h}
            icon={<ThunderboltOutlined />} iconBg="#EFF6FF" iconColor="#3B82F6" formatter={formatRub} />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="APY" value={`${pool.estimatedApy.toFixed(1)}%`}
            icon={<PercentageOutlined />} iconBg="#F3E8FF" iconColor="#8B5CF6" />
        </Col>
      </Row>

      <Card className="sber-card" title={<Text strong>Распределение ликвидности по бинам</Text>}>
        <BinLiquidityChart poolId={pool.id} />
      </Card>

      <Card className="sber-card" title={<Text strong>Параметры пула</Text>}>
        <Descriptions bordered column={{ xs: 1, sm: 2 }} size="small">
          <Descriptions.Item label="Шаг бина">{pool.binStep} bps</Descriptions.Item>
          <Descriptions.Item label="Активный бин">#{pool.activeBinId}</Descriptions.Item>
          <Descriptions.Item label="Базовая комиссия">{pool.baseFeeBps} bps</Descriptions.Item>
          <Descriptions.Item label="Динамическая комиссия">{pool.currentDynamicFeeBps} bps</Descriptions.Item>
          <Descriptions.Item label="Аккумулятор волатильности">{pool.volatilityAccumulator}</Descriptions.Item>
          <Descriptions.Item label="Комиссии X собрано">{pool.totalFeesCollectedX.toLocaleString('ru-RU')}</Descriptions.Item>
          <Descriptions.Item label="Комиссии Y собрано">{pool.totalFeesCollectedY.toLocaleString('ru-RU')}</Descriptions.Item>
          <Descriptions.Item label="Дата создания">{new Date(pool.createdAt).toLocaleDateString('ru-RU')}</Descriptions.Item>
        </Descriptions>
      </Card>
    </Space>
  )
}
