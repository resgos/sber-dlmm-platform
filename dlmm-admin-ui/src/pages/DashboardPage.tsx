import { Row, Col, Card, Statistic, Spin, Alert, Typography, Space, Table, Tag, Progress } from 'antd'
import {
  UserOutlined,
  FundOutlined,
  DollarOutlined,
  BarChartOutlined,
  CheckCircleOutlined,
  TransactionOutlined,
  TrophyOutlined,
  TeamOutlined,
  ArrowRightOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { admin, transactions, pools as poolsApi } from '@/api/services'
import StatCard, { formatRub } from '@/components/StatCard'
import { ADMIN_TILE_PALETTE } from '@/styles/palette'
import type { Pool, Transaction } from '@/api/types'

const { Title, Text } = Typography

// Sprint 9 — pulled from the Claude Design admin-dashboard mockup at
// docs/design/admin-dashboard-claude-design/. The mockup taught us that
// what was missing here wasn't "more KPI tiles" — it was "evidence of
// life": a recent-operations feed and a top-pools list make the
// dashboard read as a live system instead of a frozen snapshot. Both
// hit existing endpoints (/admin/transactions, /admin/pools) so this
// is a UI-only change. KPI tiles + hero stay as-is for this pass; a
// future iteration can lift the sparkline-equipped KPI cards from
// the mockup once we have a time-series endpoint to drive them.
const TX_STATUS_COLOR: Record<string, string> = {
  CONFIRMED: 'success',
  PENDING: 'processing',
  FAILED: 'error',
  REVERTED: 'warning',
}
const TX_STATUS_LABEL: Record<string, string> = {
  CONFIRMED: 'Исполнен',
  PENDING: 'В обработке',
  FAILED: 'Ошибка',
  REVERTED: 'Отклонён',
}
const TX_TYPE_LABEL: Record<string, string> = {
  SWAP: 'Своп',
  ADD_LIQUIDITY: 'Добавление',
  REMOVE_LIQUIDITY: 'Выход',
  CLAIM_FEE: 'Сбор комиссии',
}

// Sprint 7 dedup — StatCard + formatRub extracted to @/components/StatCard.
// Was inline in this file AND in user-ui's components/StatCard.tsx (cross-app
// dup remains for now; needs Sprint 9+ workspaces / dlmm-ui-common package).

export default function DashboardPage() {
  const navigate = useNavigate()
  const {
    data: dashboard,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['dashboard'],
    queryFn: admin.getDashboard,
    refetchInterval: 60000,
  })

  // Sprint 9 — recent-ops + top-pools feeds for the live-system feel.
  // Both refetch on the same 60s cadence as the dashboard summary.
  const { data: recentTx } = useQuery({
    queryKey: ['recent-transactions'],
    queryFn: () => transactions.getTransactions(0, 8),
    refetchInterval: 60000,
  })
  const { data: poolList } = useQuery({
    queryKey: ['dashboard-top-pools'],
    queryFn: () => poolsApi.getPools(0, 100),
    refetchInterval: 60000,
  })

  const topPoolsByVolume: Pool[] = (poolList?.content ?? [])
    .slice()
    .sort((a, b) => (b.volume24h ?? 0) - (a.volume24h ?? 0))
    .slice(0, 5)

  // Total 24h volume across the visible pool catalog — used to render
  // each pool's share-of-volume bar.
  const totalVolumeForShare = topPoolsByVolume.reduce(
    (acc, p) => acc + (p.volume24h ?? 0),
    0,
  ) || 1

  if (isLoading) {
    return (
      <div style={{ textAlign: 'center', padding: '80px 0' }}>
        <Spin size="large" tip="Загрузка дашборда..." />
      </div>
    )
  }

  if (error) {
    return (
      <Alert
        message="Не удалось загрузить дашборд"
        description="Невозможно получить метрики дашборда. Попробуйте обновить страницу."
        type="error"
        showIcon
        style={{ borderRadius: 8 }}
      />
    )
  }

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      {/* Hero — gradient lockup with platform-wide TVL */}
      <div className="sber-hero">
        <Row gutter={[24, 16]} align="middle">
          <Col xs={24} md={16}>
            <div className="sber-hero-title">Total Value Locked</div>
            <div className="sber-hero-value">{formatRub(dashboard?.totalTvlRub ?? 0)}</div>
            <div className="sber-hero-meta" style={{ marginTop: 6 }}>
              {dashboard?.activePools ?? 0} активных пул
              {(dashboard?.activePools ?? 0) === 1 ? '' : 'ов'} ·{' '}
              {(dashboard?.totalUsers ?? 0).toLocaleString('ru-RU')} пользователей ·{' '}
              объём 24ч {formatRub(dashboard?.volume24hRub ?? 0)}
            </div>
          </Col>
          <Col xs={24} md={8} style={{ textAlign: 'right' }}>
            <div className="sber-hero-title">Комиссия за всё время</div>
            <div className="sber-hero-value">{formatRub(dashboard?.totalFeesCollectedRub ?? 0)}</div>
          </Col>
        </Row>
      </div>

      {/* Row 1: Users and Pools */}
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Всего пользователей"
            value={dashboard?.totalUsers ?? 0}
            icon={<UserOutlined />}
            iconBg={ADMIN_TILE_PALETTE.users.bg}
            iconColor={ADMIN_TILE_PALETTE.users.fg}
            to="/users"
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Верифицированные"
            value={dashboard?.verifiedUsers ?? 0}
            icon={<CheckCircleOutlined />}
            iconBg={ADMIN_TILE_PALETTE.verified.bg}
            iconColor={ADMIN_TILE_PALETTE.verified.fg}
            to="/users?kycStatus=VERIFIED"
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Всего пулов"
            value={dashboard?.totalPools ?? 0}
            icon={<FundOutlined />}
            iconBg={ADMIN_TILE_PALETTE.pools.bg}
            iconColor={ADMIN_TILE_PALETTE.pools.fg}
            to="/pools"
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Активные пулы"
            value={dashboard?.activePools ?? 0}
            icon={<TeamOutlined />}
            iconBg={ADMIN_TILE_PALETTE.poolsActive.bg}
            iconColor={ADMIN_TILE_PALETTE.poolsActive.fg}
            to="/pools?status=ACTIVE"
          />
        </Col>
      </Row>

      {/* Row 2: Financial metrics */}
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Общий TVL"
            value={dashboard?.totalTvlRub ?? 0}
            icon={<DollarOutlined />}
            iconBg={ADMIN_TILE_PALETTE.tvl.bg}
            iconColor={ADMIN_TILE_PALETTE.tvl.fg}
            formatter={formatRub}
            to="/pools?sort=tvl"
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Объём за 24ч"
            value={dashboard?.volume24hRub ?? 0}
            icon={<BarChartOutlined />}
            iconBg={ADMIN_TILE_PALETTE.volume.bg}
            iconColor={ADMIN_TILE_PALETTE.volume.fg}
            formatter={formatRub}
            to="/pools?sort=volume24h"
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Собрано комиссий"
            value={dashboard?.totalFeesCollectedRub ?? 0}
            icon={<TrophyOutlined />}
            iconBg={ADMIN_TILE_PALETTE.verified.bg}
            iconColor={ADMIN_TILE_PALETTE.verified.fg}
            formatter={formatRub}
            to="/transactions?txType=CLAIM_FEE"
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Транзакций сегодня"
            value={dashboard?.transactionsToday ?? 0}
            icon={<TransactionOutlined />}
            iconBg={ADMIN_TILE_PALETTE.users.bg}
            iconColor={ADMIN_TILE_PALETTE.users.fg}
            to="/transactions?date=today"
          />
        </Col>
      </Row>

      {/* Row 3 — health summary tiles (Активные позиции / KYC).
          Sprint 9-DS — converted from two huge Statistic cards (36px
          values, 50% page width each) to compact horizontal cards with
          the value, a sub-line, and progress context. Less wasted real
          estate, same operator info. */}
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12}>
          <Card
            className="sber-card"
            style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
            styles={{ body: { padding: 18 } }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
              <div
                aria-hidden
                style={{
                  width: 56, height: 56, borderRadius: 12,
                  background: 'rgba(33,160,56,0.12)', color: 'var(--sber-green)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: 26, flexShrink: 0,
                }}
              >
                <FundOutlined />
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 12, color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.04em', fontWeight: 500, marginBottom: 2 }}>
                  Активные позиции
                </div>
                <div style={{ fontSize: 28, fontWeight: 700, color: 'var(--text-primary)', lineHeight: 1.1, fontVariantNumeric: 'tabular-nums' }}>
                  {(dashboard?.activePositions ?? 0).toLocaleString('ru-RU')}
                </div>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  Открытых LP-позиций пользователей
                </Text>
              </div>
            </div>
          </Card>
        </Col>
        <Col xs={24} sm={12}>
          <Card
            className="sber-card"
            style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
            styles={{ body: { padding: 18 } }}
          >
            {(() => {
              const verifiedPct = dashboard?.totalUsers
                ? Math.round((dashboard.verifiedUsers / dashboard.totalUsers) * 100)
                : 0
              return (
                <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
                  <div
                    aria-hidden
                    style={{
                      width: 56, height: 56, borderRadius: 12,
                      background: 'rgba(41,106,227,0.12)', color: '#296AE3',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontSize: 26, flexShrink: 0,
                    }}
                  >
                    <CheckCircleOutlined />
                  </div>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 12, color: 'var(--text-secondary)', textTransform: 'uppercase', letterSpacing: '0.04em', fontWeight: 500, marginBottom: 2 }}>
                      Уровень верификации KYC
                    </div>
                    <div style={{ fontSize: 28, fontWeight: 700, color: 'var(--text-primary)', lineHeight: 1.1, fontVariantNumeric: 'tabular-nums' }}>
                      {verifiedPct}<span style={{ fontSize: 18, fontWeight: 500, marginLeft: 2 }}>%</span>
                    </div>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {dashboard?.verifiedUsers ?? 0} из {dashboard?.totalUsers ?? 0} прошли проверку
                    </Text>
                    <div style={{
                      marginTop: 8, height: 4, borderRadius: 2,
                      background: 'rgba(229,231,235,0.7)', overflow: 'hidden',
                    }}>
                      <div style={{
                        width: `${verifiedPct}%`, height: '100%',
                        background: 'var(--sber-green)', transition: 'width 0.3s',
                      }} />
                    </div>
                  </div>
                </div>
              )
            })()}
          </Card>
        </Col>
      </Row>

      {/* Row 4 — live feeds. Last 8 swaps + top-5 pools by 24h volume.
          Both came from the Claude Design admin-dashboard mockup
          (docs/design/admin-dashboard-claude-design/) — the diagnosis
          was "dashboard reads as frozen because there's no movement"
          and these two sections are the cheapest way to show it. */}
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={14}>
          <Card
            className="sber-card"
            style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
            styles={{ body: { padding: 0 } }}
            title={
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                  <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>Последние операции</div>
                  <Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
                    Свопы и операции с ликвидностью в реальном времени
                  </Text>
                </div>
                <a
                  onClick={() => navigate('/transactions')}
                  style={{ color: 'var(--sber-green)', fontSize: 13, fontWeight: 500, cursor: 'pointer' }}
                >
                  Все транзакции <ArrowRightOutlined style={{ fontSize: 11 }} />
                </a>
              </div>
            }
          >
            <Table<Transaction>
              dataSource={recentTx?.content ?? []}
              rowKey="id"
              pagination={false}
              size="small"
              showHeader={false}
              onRow={(record) => ({
                onClick: () => navigate(`/transactions/${record.id}`),
                style: { cursor: 'pointer' },
              })}
              columns={[
                {
                  key: 'time',
                  dataIndex: 'createdAt',
                  width: 110,
                  render: (v: string) => (
                    <Text type="secondary" style={{ fontSize: 12, fontFamily: 'JetBrains Mono, monospace' }}>
                      {v ? new Date(v).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—'}
                    </Text>
                  ),
                },
                {
                  key: 'type',
                  width: 110,
                  render: (_, r) => (
                    <Text style={{ fontSize: 13, fontWeight: 500 }}>{TX_TYPE_LABEL[r.txType] || r.txType}</Text>
                  ),
                },
                {
                  key: 'amount',
                  dataIndex: 'amountIn',
                  align: 'right',
                  render: (v: number) => (
                    <span style={{ fontWeight: 500, fontVariantNumeric: 'tabular-nums' }}>
                      {(v ?? 0).toLocaleString('ru-RU', { maximumFractionDigits: 0 })}
                    </span>
                  ),
                },
                {
                  key: 'status',
                  dataIndex: 'status',
                  align: 'right',
                  width: 120,
                  render: (s: string) => (
                    <Tag color={TX_STATUS_COLOR[s] || 'default'} style={{ borderRadius: 999, padding: '0 10px' }}>
                      {TX_STATUS_LABEL[s] || s}
                    </Tag>
                  ),
                },
              ]}
              locale={{ emptyText: 'Нет недавних операций — система простаивает' }}
            />
          </Card>
        </Col>

        <Col xs={24} lg={10}>
          <Card
            className="sber-card"
            style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
            title={
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                  <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>Топ-5 пулов по объёму</div>
                  <Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
                    За последние 24 часа
                  </Text>
                </div>
                <a
                  onClick={() => navigate('/pools?sort=volume24h')}
                  style={{ color: 'var(--sber-green)', fontSize: 13, fontWeight: 500, cursor: 'pointer' }}
                >
                  Все пулы <ArrowRightOutlined style={{ fontSize: 11 }} />
                </a>
              </div>
            }
          >
            <Space direction="vertical" size={14} style={{ width: '100%' }}>
              {topPoolsByVolume.length === 0 && (
                <Text type="secondary">Нет данных об объёме</Text>
              )}
              {topPoolsByVolume.map((p) => {
                const share = ((p.volume24h ?? 0) / totalVolumeForShare) * 100
                return (
                  <div
                    key={p.id}
                    style={{ cursor: 'pointer' }}
                    onClick={() => navigate(`/pools/${p.id}`)}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                      <Text style={{ fontWeight: 500, fontSize: 13 }}>
                        {p.tokenXSymbol}/{p.tokenYSymbol}
                      </Text>
                      <Text style={{ fontWeight: 500, fontSize: 13, fontVariantNumeric: 'tabular-nums' }}>
                        {formatRub(p.volume24h ?? 0)}
                      </Text>
                    </div>
                    <Progress
                      percent={share}
                      showInfo={false}
                      strokeColor="var(--sber-green)"
                      trailColor="rgba(33,160,56,0.08)"
                      size={['100%', 6]}
                    />
                    <Text type="secondary" style={{ fontSize: 11 }}>
                      {share.toFixed(1)}% от объёма
                    </Text>
                  </div>
                )
              })}
            </Space>
          </Card>
        </Col>
      </Row>
    </Space>
  )
}
