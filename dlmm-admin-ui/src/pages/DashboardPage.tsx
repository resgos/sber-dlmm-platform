import { Row, Col, Card, Statistic, Spin, Alert, Typography, Space } from 'antd'
import {
  UserOutlined,
  FundOutlined,
  DollarOutlined,
  BarChartOutlined,
  CheckCircleOutlined,
  TransactionOutlined,
  TrophyOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { admin } from '@/api/services'
import StatCard, { formatRub } from '@/components/StatCard'
import { ADMIN_TILE_PALETTE } from '@/styles/palette'

const { Title } = Typography

// Sprint 7 dedup — StatCard + formatRub extracted to @/components/StatCard.
// Was inline in this file AND in user-ui's components/StatCard.tsx (cross-app
// dup remains for now; needs Sprint 9+ workspaces / dlmm-ui-common package).

export default function DashboardPage() {
  const {
    data: dashboard,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['dashboard'],
    queryFn: admin.getDashboard,
    refetchInterval: 60000,
  })

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
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Верифицированные"
            value={dashboard?.verifiedUsers ?? 0}
            icon={<CheckCircleOutlined />}
            iconBg={ADMIN_TILE_PALETTE.verified.bg}
            iconColor={ADMIN_TILE_PALETTE.verified.fg}
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Всего пулов"
            value={dashboard?.totalPools ?? 0}
            icon={<FundOutlined />}
            iconBg={ADMIN_TILE_PALETTE.pools.bg}
            iconColor={ADMIN_TILE_PALETTE.pools.fg}
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Активные пулы"
            value={dashboard?.activePools ?? 0}
            icon={<TeamOutlined />}
            iconBg={ADMIN_TILE_PALETTE.poolsActive.bg}
            iconColor={ADMIN_TILE_PALETTE.poolsActive.fg}
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
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="Транзакций сегодня"
            value={dashboard?.transactionsToday ?? 0}
            icon={<TransactionOutlined />}
            iconBg={ADMIN_TILE_PALETTE.users.bg}
            iconColor={ADMIN_TILE_PALETTE.users.fg}
          />
        </Col>
      </Row>

      {/* Row 3: Additional metrics */}
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12}>
          <Card
            className="sber-card"
            style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
            title={<span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>Активные позиции</span>}
          >
            <Statistic
              value={dashboard?.activePositions ?? 0}
              valueStyle={{ color: 'var(--sber-green)', fontSize: 36, fontWeight: 700 }}
              prefix={<FundOutlined />}
              suffix="позиций"
            />
          </Card>
        </Col>
        <Col xs={24} sm={12}>
          <Card
            className="sber-card"
            style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
            title={<span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>Уровень верификации KYC</span>}
          >
            <Statistic
              value={
                dashboard?.totalUsers
                  ? ((dashboard.verifiedUsers / dashboard.totalUsers) * 100).toFixed(1)
                  : 0
              }
              suffix="%"
              valueStyle={{ color: 'var(--sber-green)', fontSize: 36, fontWeight: 700 }}
              prefix={<CheckCircleOutlined />}
            />
          </Card>
        </Col>
      </Row>
    </Space>
  )
}
