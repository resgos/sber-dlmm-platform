import { useParams, useNavigate } from 'react-router-dom'
import {
  Card,
  Tag,
  Button,
  Space,
  Typography,
  Spin,
  Alert,
  Popconfirm,
  message,
  Row,
  Col,
  Tooltip as AntTooltip,
  Tabs,
} from 'antd'
import {
  ArrowLeftOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  WarningOutlined,
  CopyOutlined,
  DollarOutlined,
  RiseOutlined,
  PercentageOutlined,
  ThunderboltFilled,
  FundOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  LineChart,
  Line,
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts'
import { pools as poolService } from '@/api/services'
import type { PoolStatus } from '@/api/types'
import BinLiquidityChart from '@/components/BinLiquidityChart'
import { bpsToPercent } from '@/utils/format'
import dayjs from 'dayjs'
import { KpiRow, TokenPairChip } from '@/components/sber'
import { formatCompact, formatRub, formatTokenAmount } from '@/lib/format'

const { Text, Title } = Typography

/**
 * Sprint 9-DS — admin PoolDetailPage refactor.
 *
 * <p>Was a flat two-column shell: a giant Descriptions table with 19
 * rows + a narrow actions card on the right + two recharts down below.
 * New layout:
 *  - hero card with pair chip + status pill + dynamic-fee badge +
 *    pause/resume/shutdown actions inline
 *  - 4-up KpiRow: TVL / 24h volume / APY / current fee
 *  - tabbed body: Профиль | Аналитика | Бины
 *  - amounts formatted with `formatCompact` so the row no longer
 *    reads "108 159 991 052 360" — it reads "108.16 трлн"
 */

const poolStatusColor: Record<PoolStatus, string> = {
  ACTIVE: 'green',
  PAUSED: 'orange',
  SHUTDOWN: 'red',
  PENDING: 'blue',
}

const poolStatusLabel: Record<PoolStatus, string> = {
  ACTIVE: 'Активен',
  PAUSED: 'Приостановлен',
  SHUTDOWN: 'Остановлен',
  PENDING: 'Ожидание',
}

export default function PoolDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [messageApi, contextHolder] = message.useMessage()

  const { data: pool, isLoading, error } = useQuery({
    queryKey: ['poolDetail', id],
    queryFn: () => poolService.getPool(id!),
    enabled: !!id,
  })

  const { data: analytics } = useQuery({
    queryKey: ['poolAnalytics', id],
    queryFn: () => poolService.getPoolAnalytics(id!),
    enabled: !!id,
  })

  const pauseMutation = useMutation({
    mutationFn: () => poolService.pausePool(id!),
    onSuccess: () => {
      messageApi.success('Пул успешно приостановлен')
      queryClient.invalidateQueries({ queryKey: ['poolDetail', id] })
    },
    onError: () => messageApi.error('Не удалось приостановить пул'),
  })

  const resumeMutation = useMutation({
    mutationFn: () => poolService.resumePool(id!),
    onSuccess: () => {
      messageApi.success('Пул успешно возобновлён')
      queryClient.invalidateQueries({ queryKey: ['poolDetail', id] })
    },
    onError: () => messageApi.error('Не удалось возобновить пул'),
  })

  const shutdownMutation = useMutation({
    mutationFn: () => poolService.emergencyShutdown(id!),
    onSuccess: () => {
      messageApi.warning('Аварийная остановка пула выполнена')
      queryClient.invalidateQueries({ queryKey: ['poolDetail', id] })
    },
    onError: () => messageApi.error('Не удалось остановить пул'),
  })

  const copyId = (val: string, label: string) => {
    navigator.clipboard.writeText(val).then(
      () => messageApi.success(`${label} скопирован`),
      () => messageApi.error('Не удалось скопировать'),
    )
  }

  if (isLoading) {
    return (
      <div style={{ textAlign: 'center', padding: '80px' }}>
        <Spin size="large" />
      </div>
    )
  }

  if (error || !pool) {
    return (
      <Alert
        message="Не удалось загрузить пул"
        type="error"
        showIcon
        style={{ borderRadius: 8 }}
        action={<Button onClick={() => navigate('/pools')}>К пулам</Button>}
      />
    )
  }

  const isMutating =
    pauseMutation.isPending || resumeMutation.isPending || shutdownMutation.isPending

  // Treat the pool as RUB-denominated for TVL display when SRUB is one
  // of the legs. Otherwise show the X+Y compact sum.
  const tvlRub = pool.tokenYSymbol === 'SRUB'
    ? pool.totalTvlY + pool.totalTvlX * pool.currentPrice
    : pool.tokenXSymbol === 'SRUB'
    ? pool.totalTvlX + pool.totalTvlY / pool.currentPrice
    : pool.totalTvlX + pool.totalTvlY

  const dynamicFeeRaised = pool.currentDynamicFeeBps > pool.baseFeeBps

  return (
    <>
      {contextHolder}
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/pools')}
          style={{ padding: 0, color: 'var(--text-secondary)' }}
        >
          К списку пулов
        </Button>

        {/* Hero */}
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
                  <Tag color={poolStatusColor[pool.status]} style={{ marginInlineEnd: 0 }}>
                    {poolStatusLabel[pool.status] || pool.status}
                  </Tag>
                  {dynamicFeeRaised && (
                    <Tag color="orange" style={{ marginInlineEnd: 0 }}>
                      <ThunderboltFilled style={{ marginRight: 4 }} />
                      динамическая комиссия повышена
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
                      Активный бин: <span style={{ fontFamily: 'JetBrains Mono, monospace', color: 'var(--text-primary)' }}>{pool.activeBinId}</span>
                    </Text>
                    <Space size={4}>
                      <Text type="secondary" style={{ fontSize: 12, fontFamily: 'JetBrains Mono, monospace' }}>
                        ID …{pool.id.slice(-12)}
                      </Text>
                      <Button
                        type="text"
                        size="small"
                        icon={<CopyOutlined />}
                        onClick={() => copyId(pool.id, 'ID пула')}
                        aria-label="Скопировать ID"
                        style={{ padding: '0 4px', color: 'var(--text-muted)' }}
                      />
                    </Space>
                  </Space>
                </div>
              </Col>
              <Col flex="none">
                <Space wrap>
                  {pool.status === 'ACTIVE' && (
                    <Popconfirm
                      title="Приостановить пул?"
                      description="Торговля будет приостановлена до возобновления пула."
                      onConfirm={() => pauseMutation.mutate()}
                      okText="Да, приостановить"
                      cancelText="Отмена"
                    >
                      <Button
                        icon={<PauseCircleOutlined />}
                        loading={pauseMutation.isPending}
                        disabled={isMutating}
                        style={{ borderRadius: 8 }}
                      >
                        Пауза
                      </Button>
                    </Popconfirm>
                  )}
                  {pool.status === 'PAUSED' && (
                    <Popconfirm
                      title="Возобновить пул?"
                      description="Торговля для этого пула будет возобновлена."
                      onConfirm={() => resumeMutation.mutate()}
                      okText="Да, возобновить"
                      cancelText="Отмена"
                    >
                      <Button
                        type="primary"
                        icon={<PlayCircleOutlined />}
                        loading={resumeMutation.isPending}
                        disabled={isMutating}
                        style={{ borderRadius: 8 }}
                      >
                        Возобновить
                      </Button>
                    </Popconfirm>
                  )}
                  {pool.status !== 'SHUTDOWN' && (
                    <Popconfirm
                      title="Аварийная остановка?"
                      description="Это действие необратимо. Пул будет окончательно остановлен."
                      onConfirm={() => shutdownMutation.mutate()}
                      okText="Да, остановить"
                      cancelText="Отмена"
                      okButtonProps={{ danger: true }}
                    >
                      <Button
                        danger
                        icon={<WarningOutlined />}
                        loading={shutdownMutation.isPending}
                        disabled={isMutating}
                        style={{ borderRadius: 8 }}
                      >
                        Shutdown
                      </Button>
                    </Popconfirm>
                  )}
                  {pool.status === 'SHUTDOWN' && (
                    <Tag color="red" style={{ padding: '6px 12px', borderRadius: 8 }}>
                      Пул окончательно остановлен
                    </Tag>
                  )}
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
              sub: 'свопов за последние сутки',
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
              label: 'Текущая комиссия',
              value: bpsToPercent(pool.currentDynamicFeeBps),
              sub: dynamicFeeRaised
                ? `база ${bpsToPercent(pool.baseFeeBps)} · повышена`
                : `совпадает с базовой`,
              icon: <FundOutlined style={{ color: dynamicFeeRaised ? '#F2994A' : '#6B7280' }} />,
              accent: dynamicFeeRaised ? '#F2994A' : undefined,
            },
          ]}
        />

        <Card
          className="sber-card"
          style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
          styles={{ body: { padding: '8px 16px 16px' } }}
        >
          <Tabs
            defaultActiveKey="profile"
            items={[
              {
                key: 'profile',
                label: 'Профиль',
                children: (
                  <Row gutter={[16, 16]}>
                    <Col xs={24} md={12}>
                      <ProfileRow label="ID пула" value={pool.id} mono small />
                      <ProfileRow label={`ID ${pool.tokenXSymbol}`} value={pool.tokenXId} mono small />
                      <ProfileRow label={`ID ${pool.tokenYSymbol}`} value={pool.tokenYId} mono small />
                      <ProfileRow
                        label="Создан"
                        value={dayjs(pool.createdAt).format('DD.MM.YYYY HH:mm:ss')}
                        mono
                      />
                    </Col>
                    <Col xs={24} md={12}>
                      <ProfileRow
                        label="Текущая цена"
                        value={`${pool.currentPrice.toLocaleString('ru-RU', { maximumFractionDigits: 6 })} ${pool.tokenYSymbol}/${pool.tokenXSymbol}`}
                      />
                      <ProfileRow
                        label={`Резерв ${pool.tokenXSymbol}`}
                        value={formatTokenAmount(pool.totalTvlX, pool.tokenXSymbol, { compact: true })}
                      />
                      <ProfileRow
                        label={`Резерв ${pool.tokenYSymbol}`}
                        value={formatTokenAmount(pool.totalTvlY, pool.tokenYSymbol, { compact: true })}
                      />
                      <ProfileRow
                        label="Индекс волатильности"
                        value={String(pool.volatilityAccumulator)}
                        mono
                      />
                    </Col>
                    <Col xs={24} md={12}>
                      <ProfileRow
                        label={`Собрано комиссий в ${pool.tokenXSymbol}`}
                        value={formatTokenAmount(pool.totalFeesCollectedX, pool.tokenXSymbol, { compact: true })}
                      />
                    </Col>
                    <Col xs={24} md={12}>
                      <ProfileRow
                        label={`Собрано комиссий в ${pool.tokenYSymbol}`}
                        value={formatTokenAmount(pool.totalFeesCollectedY, pool.tokenYSymbol, { compact: true })}
                      />
                    </Col>
                  </Row>
                ),
              },
              {
                key: 'analytics',
                label: 'Аналитика',
                children: analytics ? (
                  <Row gutter={[16, 16]}>
                    <Col xs={24} lg={12}>
                      <Card
                        size="small"
                        title={<Text strong>История TVL</Text>}
                        style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
                      >
                        <ResponsiveContainer width="100%" height={240}>
                          <AreaChart data={analytics.tvlHistory}>
                            <CartesianGrid strokeDasharray="3 3" stroke="#E5E7EB" />
                            <XAxis
                              dataKey="date"
                              tickFormatter={(d) => dayjs(d).format('MM-DD')}
                              tick={{ fontSize: 11, fill: '#6B7280' }}
                            />
                            <YAxis
                              tick={{ fontSize: 11, fill: '#6B7280' }}
                              tickFormatter={(v) => formatCompact(v)}
                            />
                            <Tooltip
                              formatter={(v: number) => [formatCompact(v), 'TVL']}
                              labelFormatter={(l) => dayjs(l).format('DD.MM.YYYY')}
                            />
                            <Area
                              type="monotone"
                              dataKey="tvl"
                              stroke="#21A038"
                              fill="rgba(33, 160, 56, 0.1)"
                              strokeWidth={2}
                            />
                          </AreaChart>
                        </ResponsiveContainer>
                      </Card>
                    </Col>
                    <Col xs={24} lg={12}>
                      <Card
                        size="small"
                        title={<Text strong>История объёмов</Text>}
                        style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
                      >
                        <ResponsiveContainer width="100%" height={240}>
                          <LineChart data={analytics.volumeHistory}>
                            <CartesianGrid strokeDasharray="3 3" stroke="#E5E7EB" />
                            <XAxis
                              dataKey="date"
                              tickFormatter={(d) => dayjs(d).format('MM-DD')}
                              tick={{ fontSize: 11, fill: '#6B7280' }}
                            />
                            <YAxis
                              tick={{ fontSize: 11, fill: '#6B7280' }}
                              tickFormatter={(v) => formatCompact(v)}
                            />
                            <Tooltip
                              formatter={(v: number) => [formatCompact(v), 'Объём']}
                              labelFormatter={(l) => dayjs(l).format('DD.MM.YYYY')}
                            />
                            <Legend />
                            <Line
                              type="monotone"
                              dataKey="volume"
                              stroke="#F59E0B"
                              strokeWidth={2}
                              dot={false}
                            />
                          </LineChart>
                        </ResponsiveContainer>
                      </Card>
                    </Col>
                  </Row>
                ) : (
                  <Text type="secondary">Загрузка аналитики…</Text>
                ),
              },
              {
                key: 'bins',
                label: 'Распределение по бинам',
                children: (
                  <Card
                    size="small"
                    style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
                    styles={{ body: { padding: 0 } }}
                  >
                    <BinLiquidityChart poolId={id!} />
                  </Card>
                ),
              },
            ]}
          />
        </Card>
      </Space>
    </>
  )
}

function ProfileRow({
  label,
  value,
  mono,
  small,
}: {
  label: string
  value: string
  mono?: boolean
  small?: boolean
}) {
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
          fontSize: small ? 11 : 13,
          fontWeight: 500,
          textAlign: 'right',
          fontFamily: mono ? 'JetBrains Mono, monospace' : undefined,
          maxWidth: '70%',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        {value}
      </Text>
    </div>
  )
}
