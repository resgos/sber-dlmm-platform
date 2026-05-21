import { useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { Card, Typography, Space, Tag, Button, Spin, Alert, Row, Col, Tabs, message } from 'antd'
import {
  ArrowLeftOutlined,
  PlusOutlined,
  DollarOutlined,
  RiseOutlined,
  PercentageOutlined,
  FundOutlined,
  ThunderboltFilled,
  PieChartOutlined,
  WarningOutlined,
  DeleteOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { pools, fees } from '@/api/services'
import type { Position } from '@/api/types'
import BinLiquidityChart from '@/components/BinLiquidityChart'
import PoolSwapPanel from '@/components/PoolSwapPanel'
import { bpsToPercent } from '@/utils/format'
import { KpiRow, KpiTile, TokenPairChip } from '@/components/sber'
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
  const queryClient = useQueryClient()

  const { data: pool, isLoading, error } = useQuery({
    queryKey: ['poolDetail', id],
    queryFn: () => pools.getPool(id!),
    enabled: !!id,
  })

  // Sprint 9-DS-r3 — my positions in this pool (Meteora-style inline
  // "control surface": every position with its claimable fees + Add /
  // Claim / Remove actions). Saves a round-trip to /positions.
  const { data: myPositions } = useQuery({
    queryKey: ['myPositions'],
    queryFn: pools.getMyPositions,
  })
  const poolPositions = useMemo<Position[]>(
    () => (myPositions ?? []).filter((p) => p.poolId === id && p.isActive),
    [myPositions, id],
  )

  const claimMutation = useMutation({
    mutationFn: (positionId: string) => fees.claimFees({ positionId }),
    onSuccess: () => {
      message.success('Комиссии забраны')
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myFeeSummary'] })
    },
    onError: (e: any) => message.error(e?.response?.data?.message || 'Не удалось забрать комиссии'),
  })

  if (isLoading) return <div style={{ textAlign: 'center', padding: '80px 0' }}><Spin size="large" /></div>
  if (error || !pool) return <Alert message="Пул не найден" type="error" showIcon />

  const dynamicFeeRaised = pool.currentDynamicFeeBps > pool.baseFeeBps
  const tvlRub = pool.tokenYSymbol === 'SRUB'
    ? pool.totalTvlY + pool.totalTvlX * pool.currentPrice
    : pool.tokenXSymbol === 'SRUB'
    ? pool.totalTvlX + pool.totalTvlY / pool.currentPrice
    : pool.totalTvlX + pool.totalTvlY

  // Sprint 9-DS-r3 — Meteora "Out-of-range" detection. A position is
  // out-of-range when the active bin lies outside its [binRangeMin,
  // binRangeMax]. Out-of-range positions earn 0 fees — surface this
  // loudly so the user knows to rebalance.
  const outOfRangeCount = poolPositions.filter(
    (p) => pool.activeBinId < p.binRangeMin || pool.activeBinId > p.binRangeMax,
  ).length

  // Aggregate "my share" — sum of my liquidity in this pool versus the
  // pool's total. Liquidity values are normalised already (totalTvlX/Y),
  // so we sum currentValueX/Y from each position and compare to TVL.
  const myShareValueRub = poolPositions.reduce((acc, p) => {
    const valX = p.currentValueX ?? 0
    const valY = p.currentValueY ?? 0
    if (pool.tokenYSymbol === 'SRUB') return acc + valY + valX * pool.currentPrice
    if (pool.tokenXSymbol === 'SRUB') return acc + valX + valY / pool.currentPrice
    return acc + valX + valY
  }, 0)
  const sharePct = tvlRub > 0 ? Math.min(100, (myShareValueRub / tvlRub) * 100) : 0

  const userBinRanges = poolPositions.map((p) => ({
    binMin: p.binRangeMin,
    binMax: p.binRangeMax,
  }))

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
                {/* Sprint 9-DS-r3 — removed the "Обмен" button that
                    jumped to /swap; swap is now embedded right on
                    this page (PoolSwapPanel) per Meteora pattern. */}
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
          // Sprint 9-DS-r3 — replaced "Текущая цена" tile (already shown in
          // hero + bin chart) with "Моя доля". Meteora-style: tells the LP
          // how big a slice of the pool they own.
          {
            label: 'Моя доля',
            value: poolPositions.length === 0
              ? '—'
              : sharePct < 0.01
              ? '< 0,01%'
              : `${sharePct.toFixed(2)}%`,
            sub: poolPositions.length === 0
              ? 'у вас нет позиций'
              : `${poolPositions.length} позиц${poolPositions.length === 1 ? 'ия' : poolPositions.length < 5 ? 'ии' : 'ий'} · ${formatCompact(myShareValueRub)} ₽`,
            icon: <PieChartOutlined style={{ color: '#9333EA' }} />,
            accent: poolPositions.length > 0 ? '#9333EA' : undefined,
          },
        ]}
      />

      {/* Sprint 9-DS-r3 — out-of-range warning (Meteora pattern). When
          the active bin sits outside any of the user's ranges, that
          capital earns 0 fees. Surface loudly. */}
      {outOfRangeCount > 0 && (
        <Alert
          message={`${outOfRangeCount} ваших позиций — вне диапазона цены`}
          description="Позиция вне диапазона не получает комиссий. Откройте «Добавить ликвидность» и перенесите ликвидность ближе к текущей цене (rebalance)."
          type="warning"
          showIcon
          icon={<WarningOutlined />}
          action={
            <Button
              size="small"
              onClick={() => navigate(`/pools/${id}/liquidity`)}
              style={{ borderRadius: 8 }}
            >
              Ребаланс
            </Button>
          }
          style={{ borderRadius: 12 }}
        />
      )}

      {/* Sprint 9-DS-r3 — 2-col layout: bin chart (with user bins
          overlaid) + my positions on the left, embedded swap on the
          right. Mirrors Meteora's Dynamic Terminal: stay on the pool
          page, do everything without bouncing. */}
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={16}>
          <Card
            className="sber-card"
            style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
            title={
              <Space size={8}>
                <Text strong>Распределение ликвидности</Text>
                {userBinRanges.length > 0 && (
                  <Tag color="purple" style={{ borderRadius: 999, fontSize: 11 }}>
                    ваши бины подсвечены
                  </Tag>
                )}
              </Space>
            }
          >
            <BinLiquidityChart poolId={pool.id} userBinRanges={userBinRanges} />
          </Card>

          {poolPositions.length > 0 && (
            <Card
              className="sber-card"
              style={{ borderRadius: 12, border: '1px solid var(--border-light)', marginTop: 16 }}
              title={
                <Space size={8}>
                  <PieChartOutlined style={{ color: '#9333EA' }} />
                  <Text strong>Мои позиции в этом пуле</Text>
                  <Tag style={{ borderRadius: 999 }}>{poolPositions.length}</Tag>
                </Space>
              }
              extra={
                <Button
                  size="small"
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={() => navigate(`/pools/${id}/liquidity`)}
                  style={{ borderRadius: 8 }}
                >
                  Добавить
                </Button>
              }
              styles={{ body: { padding: 0 } }}
            >
              <div style={{ maxHeight: 320, overflowY: 'auto' }}>
                {poolPositions.map((p, i) => {
                  const outOfRange =
                    pool.activeBinId < p.binRangeMin || pool.activeBinId > p.binRangeMax
                  const hasFees = (p.unclaimedFeeX ?? 0) > 0 || (p.unclaimedFeeY ?? 0) > 0
                  return (
                    <div
                      key={p.id}
                      style={{
                        padding: '12px 16px',
                        borderTop: i === 0 ? 'none' : '1px solid var(--border-light)',
                        display: 'flex',
                        alignItems: 'center',
                        gap: 12,
                        flexWrap: 'wrap',
                      }}
                    >
                      <div style={{ minWidth: 0, flex: '1 1 200px' }}>
                        <Space size={6}>
                          <Tag color="blue" style={{ marginRight: 0 }}>{p.strategy}</Tag>
                          {outOfRange && (
                            <Tag color="orange" style={{ marginRight: 0 }} icon={<WarningOutlined />}>
                              вне диапазона
                            </Tag>
                          )}
                        </Space>
                        <div style={{ fontSize: 11, color: 'var(--text-secondary)', marginTop: 2, fontFamily: 'JetBrains Mono, monospace' }}>
                          бины {p.binRangeMin} — {p.binRangeMax}
                        </div>
                      </div>
                      <div style={{ minWidth: 0, flex: '1 1 200px', textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                        {hasFees ? (
                          <>
                            <div style={{ fontSize: 12, color: 'var(--sber-green)', fontWeight: 600 }}>
                              +{formatTokenAmount(p.unclaimedFeeX, pool.tokenXSymbol, { compact: true })}
                              {' · '}
                              +{formatTokenAmount(p.unclaimedFeeY, pool.tokenYSymbol, { compact: true })}
                            </div>
                            <div style={{ fontSize: 10, color: 'var(--text-muted)' }}>незабранные</div>
                          </>
                        ) : (
                          <Text type="secondary" style={{ fontSize: 11 }}>нет комиссий к получению</Text>
                        )}
                      </div>
                      <Space size={6}>
                        <Button
                          size="small"
                          type="primary"
                          ghost
                          icon={<DollarOutlined />}
                          disabled={!hasFees || claimMutation.isPending}
                          loading={claimMutation.isPending && claimMutation.variables === p.id}
                          onClick={() => claimMutation.mutate(p.id)}
                        >
                          Забрать
                        </Button>
                        <Button
                          size="small"
                          danger
                          icon={<DeleteOutlined />}
                          onClick={() => navigate(`/pools/${id}/liquidity`)}
                        >
                          Снять
                        </Button>
                      </Space>
                    </div>
                  )
                })}
              </div>
            </Card>
          )}
        </Col>

        <Col xs={24} xl={8}>
          <PoolSwapPanel pool={pool} />
        </Col>
      </Row>

      <Card
        className="sber-card"
        style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
        styles={{ body: { padding: '8px 16px 16px' } }}
      >
        <Tabs
          defaultActiveKey="params"
          items={[
            {
              key: 'params',
              label: 'Параметры пула',
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
