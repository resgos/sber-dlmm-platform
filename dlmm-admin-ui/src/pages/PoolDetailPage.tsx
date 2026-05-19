import { useParams, useNavigate } from 'react-router-dom'
import {
  Card,
  Descriptions,
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
} from 'antd'
import {
  ArrowLeftOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  WarningOutlined,
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

const { Title } = Typography

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

  return (
    <>
      {contextHolder}
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/pools')} style={{ borderRadius: 8 }}>
            Назад
          </Button>
          <Title level={4} className="sber-page-title">
            Пул: {pool.tokenXSymbol}/{pool.tokenYSymbol}
          </Title>
          <Tag color={poolStatusColor[pool.status]}>{poolStatusLabel[pool.status] || pool.status}</Tag>
        </div>

        <Row gutter={[16, 16]}>
          <Col xs={24} lg={16}>
            <Card
              className="sber-card"
              title={<span style={{ fontWeight: 600 }}>Информация о пуле</span>}
              style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
            >
              <Descriptions column={{ xs: 1, sm: 2 }} bordered size="small">
                <Descriptions.Item label="ID" span={2}>
                  {pool.id}
                </Descriptions.Item>
                <Descriptions.Item label="ID токена X">{pool.tokenXId}</Descriptions.Item>
                <Descriptions.Item label="ID токена Y">{pool.tokenYId}</Descriptions.Item>
                <Descriptions.Item label="Символ токена X">
                  <strong>{pool.tokenXSymbol}</strong>
                </Descriptions.Item>
                <Descriptions.Item label="Символ токена Y">
                  <strong>{pool.tokenYSymbol}</strong>
                </Descriptions.Item>
                <Descriptions.Item label="Шаг цены между бинами">{bpsToPercent(pool.binStep)}</Descriptions.Item>
                <Descriptions.Item label="Базовая комиссия">{bpsToPercent(pool.baseFeeBps)}</Descriptions.Item>
                <Descriptions.Item label="Текущая цена">
                  {pool.currentPrice.toLocaleString('ru-RU', { maximumFractionDigits: 6 })}
                  <span style={{ marginLeft: 8, color: 'var(--text-secondary)', fontSize: 12 }}>
                    {pool.tokenYSymbol} за 1 {pool.tokenXSymbol}
                  </span>
                </Descriptions.Item>
                <Descriptions.Item label={`Резерв ${pool.tokenXSymbol}`}>
                  {pool.totalTvlX.toLocaleString('ru-RU', { maximumFractionDigits: 2 })}
                </Descriptions.Item>
                <Descriptions.Item label={`Резерв ${pool.tokenYSymbol}`}>
                  {pool.totalTvlY.toLocaleString('ru-RU', { maximumFractionDigits: 2 })}
                </Descriptions.Item>
                <Descriptions.Item label="Объём за 24ч">
                  {pool.volume24h.toLocaleString('ru-RU', { maximumFractionDigits: 2 })}
                </Descriptions.Item>
                <Descriptions.Item label="Ожидаемый APY">
                  <span style={{ color: '#21A038', fontWeight: 600 }}>
                    {pool.estimatedApy.toFixed(2)}%
                  </span>
                </Descriptions.Item>
                <Descriptions.Item label="Статус">
                  <Tag color={poolStatusColor[pool.status]}>{poolStatusLabel[pool.status] || pool.status}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="Дата создания">
                  {dayjs(pool.createdAt).format('YYYY-MM-DD HH:mm:ss')}
                </Descriptions.Item>
                <Descriptions.Item label="Текущая комиссия">
                  {bpsToPercent(pool.currentDynamicFeeBps)}
                  {pool.currentDynamicFeeBps > pool.baseFeeBps && (
                    <span style={{ marginLeft: 8, color: '#F59E0B', fontSize: 12 }}>
                      ↑ повышена из-за волатильности
                    </span>
                  )}
                </Descriptions.Item>
                <Descriptions.Item label="Индекс волатильности">
                  {pool.volatilityAccumulator}
                </Descriptions.Item>
                <Descriptions.Item label={`Собрано комиссий в ${pool.tokenXSymbol}`}>
                  {pool.totalFeesCollectedX.toLocaleString('ru-RU', {
                    maximumFractionDigits: 4,
                  })}
                </Descriptions.Item>
                <Descriptions.Item label={`Собрано комиссий в ${pool.tokenYSymbol}`}>
                  {pool.totalFeesCollectedY.toLocaleString('ru-RU', {
                    maximumFractionDigits: 4,
                  })}
                </Descriptions.Item>
                <Descriptions.Item label="Внутренний ID бина">
                  <span style={{ fontFamily: 'monospace', fontSize: 11, color: 'var(--text-muted)' }}>
                    {pool.activeBinId}
                  </span>
                </Descriptions.Item>
              </Descriptions>
            </Card>
          </Col>

          <Col xs={24} lg={8}>
            <Card
              className="sber-card"
              title={<span style={{ fontWeight: 600 }}>Действия администратора</span>}
              style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
            >
              <Space direction="vertical" style={{ width: '100%' }} size={12}>
                {pool.status === 'ACTIVE' && (
                  <Popconfirm
                    title="Приостановить пул?"
                    description="Торговля будет приостановлена до возобновления пула."
                    onConfirm={() => pauseMutation.mutate()}
                    okText="Да, приостановить"
                    cancelText="Отмена"
                  >
                    <Button
                      block
                      icon={<PauseCircleOutlined />}
                      loading={pauseMutation.isPending}
                      disabled={isMutating}
                      style={{ borderRadius: 8 }}
                    >
                      Приостановить пул
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
                      block
                      icon={<PlayCircleOutlined />}
                      loading={resumeMutation.isPending}
                      disabled={isMutating}
                      style={{ borderRadius: 8 }}
                    >
                      Возобновить пул
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
                      block
                      icon={<WarningOutlined />}
                      loading={shutdownMutation.isPending}
                      disabled={isMutating}
                      style={{ borderRadius: 8 }}
                    >
                      Аварийная остановка
                    </Button>
                  </Popconfirm>
                )}

                {pool.status === 'SHUTDOWN' && (
                  <Tag color="red" style={{ width: '100%', textAlign: 'center', padding: '8px', borderRadius: 8 }}>
                    Пул окончательно остановлен
                  </Tag>
                )}
              </Space>
            </Card>
          </Col>
        </Row>

        <Card
          className="sber-card"
          title={<span style={{ fontWeight: 600 }}>Распределение ликвидности по бинам</span>}
          style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
        >
          <BinLiquidityChart poolId={id!} />
        </Card>

        {analytics && (
          <Row gutter={[16, 16]}>
            <Col xs={24} lg={12}>
              <Card
                className="sber-card"
                title={<span style={{ fontWeight: 600 }}>История TVL</span>}
                style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
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
                      tickFormatter={(v) =>
                        v >= 1000000
                          ? `${(v / 1000000).toFixed(1)}M`
                          : v >= 1000
                          ? `${(v / 1000).toFixed(1)}K`
                          : String(v)
                      }
                    />
                    <Tooltip
                      formatter={(v: number) => [v.toLocaleString('ru-RU'), 'TVL']}
                      labelFormatter={(l) => dayjs(l).format('YYYY-MM-DD')}
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
                className="sber-card"
                title={<span style={{ fontWeight: 600 }}>История объёмов</span>}
                style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
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
                      tickFormatter={(v) =>
                        v >= 1000000
                          ? `${(v / 1000000).toFixed(1)}M`
                          : v >= 1000
                          ? `${(v / 1000).toFixed(1)}K`
                          : String(v)
                      }
                    />
                    <Tooltip
                      formatter={(v: number) => [v.toLocaleString('ru-RU'), 'Объём']}
                      labelFormatter={(l) => dayjs(l).format('YYYY-MM-DD')}
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
        )}
      </Space>
    </>
  )
}
