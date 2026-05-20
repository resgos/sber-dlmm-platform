import { useParams, useNavigate } from 'react-router-dom'
import { Card, Typography, Space, Tag, Button, Spin, Alert, Row, Col, Tabs } from 'antd'
import {
  ArrowLeftOutlined,
  PlusOutlined,
  DollarOutlined,
  RiseOutlined,
  PercentageOutlined,
  FundOutlined,
  ThunderboltFilled,
  SwapOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { pools } from '@/api/services'
import BinLiquidityChart from '@/components/BinLiquidityChart'
import { bpsToPercent } from '@/utils/format'
import { KpiRow, TokenPairChip } from '@/components/sber'
import { formatCompact, formatRub, formatTokenAmount } from '@/lib/format'
import dayjs from 'dayjs'

const { Title, Text } = Typography

const statusColors: Record<string, string> = {
  ACTIVE: 'success',
  PAUSED: 'warning',
  SHUTDOWN: 'error',
  PENDING: 'processing',
}

/**
 * Sprint 9-DS — user-facing PoolDetailPage refactor.
 *
 * <p>Matches the admin PoolDetail layout: hero card with pair chip +
 * status + dynamic-fee badge + Add Liquidity action; 4-up KpiRow for
 * the live metrics; tabbed body with profile + bin chart.
 */
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

  const dynamicFeeRaised = pool.currentDynamicFeeBps > pool.baseFeeBps
  const tvlRub = pool.tokenYSymbol === 'SRUB'
    ? pool.totalTvlY + pool.totalTvlX * pool.currentPrice
    : pool.tokenXSymbol === 'SRUB'
    ? pool.totalTvlX + pool.totalTvlY / pool.currentPrice
    : pool.totalTvlX + pool.totalTvlY

  return (
    <Space direction="vertical" size={20} style={{ width: '100%' }}>
      <Button
        type="text"
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate('/pools')}
        style={{ padding: 0, color: 'var(--text-secondary)' }}
      >
        К списку пулов
      </Button>

      <Card
        className="sber-card"
        style={{ borderRadius: 16, border: '1px solid var(--border-light)', overflow: 'hidden' }}
        styles={{ body: { padding: 0 } }}
      >
        <div
          style={{
            padding: '24px 28px',
            background: 'linear-gradient(135deg, rgba(33,160,56,0.10) 0%, rgba(33,160,56,0.03) 100%)',
            borderBottom: '1px solid var(--border-light)',
          }}
        >
          <Row align="middle" gutter={[24, 16]}>
            <Col flex="auto" style={{ minWidth: 0 }}>
              <Space size={10} wrap align="center">
                <Title level={4} className="sber-page-title" style={{ margin: 0 }}>
                  <TokenPairChip x={pool.tokenXSymbol} y={pool.tokenYSymbol} size="lg" />
                </Title>
                <Tag color={statusColors[pool.status]} style={{ marginInlineEnd: 0 }}>
                  {pool.status}
                </Tag>
                {dynamicFeeRaised && (
                  <Tag color="orange" style={{ marginInlineEnd: 0 }}>
                    <ThunderboltFilled style={{ marginRight: 4 }} />
                    комиссия повышена
                  </Tag>
                )}
              </Space>
              <div style={{ marginTop: 8 }}>
                <Space size={16} wrap>
                  <Text type="secondary" style={{ fontSize: 13 }}>
                    Шаг бина: <strong style={{ color: 'var(--text-primary)' }}>{bpsToPercent(pool.binStep)}</strong>
                  </Text>
                  <Text type="secondary" style={{ fontSize: 13 }}>
                    Базовая комиссия: <strong style={{ color: 'var(--text-primary)' }}>{bpsToPercent(pool.baseFeeBps)}</strong>
                  </Text>
                  <Text type="secondary" style={{ fontSize: 13 }}>
                    Создан: <strong style={{ color: 'var(--text-primary)' }}>{dayjs(pool.createdAt).format('DD.MM.YYYY')}</strong>
                  </Text>
                </Space>
              </div>
            </Col>
            <Col flex="none">
              <Space>
                <Button
                  icon={<SwapOutlined />}
                  onClick={() => navigate('/swap')}
                  style={{ borderRadius: 8 }}
                >
                  Обмен
                </Button>
                <Button
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={() => navigate(`/pools/${id}/liquidity`)}
                  style={{ borderRadius: 8 }}
                >
                  Добавить ликвидность
                </Button>
              </Space>
            </Col>
          </Row>
        </div>
      </Card>

      <KpiRow
        tiles={[
          {
            label: 'TVL пула',
            value: formatRub(tvlRub),
            sub: `${formatCompact(pool.totalTvlX)} ${pool.tokenXSymbol} + ${formatCompact(pool.totalTvlY)} ${pool.tokenYSymbol}`,
            icon: <DollarOutlined style={{ color: 'var(--sber-green)' }} />,
            accent: 'var(--sber-green)',
          },
          {
            label: 'Объём за 24ч',
            value: formatCompact(pool.volume24h ?? 0),
            sub: 'свопов за сутки',
            icon: <RiseOutlined style={{ color: '#296AE3' }} />,
          },
          {
            label: 'Расч. APY',
            value: pool.estimatedApy > 0 ? `${pool.estimatedApy.toFixed(2)}%` : '—',
            sub: pool.estimatedApy > 0 ? 'для LP-провайдеров' : 'недостаточно данных',
            icon: <PercentageOutlined style={{ color: '#9B59B6' }} />,
            accent: pool.estimatedApy > 0 ? 'var(--sber-green)' : undefined,
          },
          {
            label: 'Текущая цена',
            value: pool.currentPrice.toLocaleString('ru-RU', { maximumFractionDigits: 4 }),
            sub: `${pool.tokenYSymbol} за 1 ${pool.tokenXSymbol}`,
            icon: <FundOutlined style={{ color: '#F2994A' }} />,
          },
        ]}
      />

      <Card
        className="sber-card"
        style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
        styles={{ body: { padding: '8px 16px 16px' } }}
      >
        <Tabs
          defaultActiveKey="bins"
          items={[
            {
              key: 'bins',
              label: 'Распределение по бинам',
              children: (
                <Card
                  size="small"
                  style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
                  styles={{ body: { padding: 0 } }}
                >
                  <BinLiquidityChart poolId={pool.id} />
                </Card>
              ),
            },
            {
              key: 'params',
              label: 'Параметры',
              children: (
                <Row gutter={[16, 16]}>
                  <Col xs={24} md={12}>
                    <ProfileRow label="Шаг цены между бинами" value={bpsToPercent(pool.binStep)} />
                    <ProfileRow
                      label="Базовая комиссия"
                      value={bpsToPercent(pool.baseFeeBps)}
                    />
                    <ProfileRow
                      label="Текущая комиссия"
                      value={`${bpsToPercent(pool.currentDynamicFeeBps)}${dynamicFeeRaised ? ' (повышена)' : ''}`}
                    />
                    <ProfileRow
                      label="Текущая цена"
                      value={`${pool.currentPrice.toLocaleString('ru-RU', { maximumFractionDigits: 6 })} ${pool.tokenYSymbol}/${pool.tokenXSymbol}`}
                    />
                  </Col>
                  <Col xs={24} md={12}>
                    <ProfileRow
                      label={`Резерв ${pool.tokenXSymbol}`}
                      value={formatTokenAmount(pool.totalTvlX, pool.tokenXSymbol, { compact: true })}
                    />
                    <ProfileRow
                      label={`Резерв ${pool.tokenYSymbol}`}
                      value={formatTokenAmount(pool.totalTvlY, pool.tokenYSymbol, { compact: true })}
                    />
                    <ProfileRow
                      label={`Комиссии собрано в ${pool.tokenXSymbol}`}
                      value={formatTokenAmount(pool.totalFeesCollectedX, pool.tokenXSymbol, { compact: true })}
                    />
                    <ProfileRow
                      label={`Комиссии собрано в ${pool.tokenYSymbol}`}
                      value={formatTokenAmount(pool.totalFeesCollectedY, pool.tokenYSymbol, { compact: true })}
                    />
                  </Col>
                </Row>
              ),
            },
          ]}
        />
      </Card>
    </Space>
  )
}

function ProfileRow({ label, value }: { label: string; value: string }) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'baseline',
        padding: '10px 0',
        borderBottom: '1px solid var(--border-light)',
        gap: 12,
      }}
    >
      <Text type="secondary" style={{ fontSize: 12 }}>{label}</Text>
      <Text
        style={{
          fontSize: 13,
          fontWeight: 500,
          textAlign: 'right',
          fontVariantNumeric: 'tabular-nums',
          maxWidth: '70%',
        }}
      >
        {value}
      </Text>
    </div>
  )
}
