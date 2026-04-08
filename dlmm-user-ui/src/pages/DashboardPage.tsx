import { Row, Col, Card, Table, Tag, Space, Typography, Spin, Alert, Button } from 'antd'
import {
  WalletOutlined,
  PieChartOutlined,
  DollarOutlined,
  TrophyOutlined,
  ArrowRightOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { balances, pools, fees, transactions, oracle } from '@/api/services'
import StatCard, { formatRub } from '@/components/StatCard'
import type { TokenBalance, Position, Transaction, TokenPrice } from '@/api/types'
import dayjs from 'dayjs'

const { Title, Text } = Typography

const txTypeLabels: Record<string, { text: string; color: string }> = {
  SWAP: { text: 'Обмен', color: 'blue' },
  ADD_LIQUIDITY: { text: 'Добавление', color: 'green' },
  REMOVE_LIQUIDITY: { text: 'Удаление', color: 'orange' },
  CLAIM_FEE: { text: 'Комиссии', color: 'gold' },
  TRANSFER: { text: 'Перевод', color: 'purple' },
  MINT: { text: 'Выпуск', color: 'cyan' },
  BURN: { text: 'Сжигание', color: 'red' },
}

const statusLabels: Record<string, { text: string; color: string }> = {
  PENDING: { text: 'Ожидание', color: 'processing' },
  CONFIRMED: { text: 'Подтверждена', color: 'success' },
  FAILED: { text: 'Ошибка', color: 'error' },
  CANCELLED: { text: 'Отменена', color: 'default' },
}

export default function DashboardPage() {
  const navigate = useNavigate()

  const { data: myBalances, isLoading: loadingBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })

  const { data: myPositions } = useQuery({
    queryKey: ['myPositions'],
    queryFn: pools.getMyPositions,
  })

  const { data: feeSummary } = useQuery({
    queryKey: ['myFeeSummary'],
    queryFn: fees.getMyFeeSummary,
  })

  const { data: recentTx } = useQuery({
    queryKey: ['myTransactions', 0, 5],
    queryFn: () => transactions.getMyTransactions(0, 5),
  })

  const { data: prices } = useQuery({
    queryKey: ['tokenPrices'],
    queryFn: oracle.getPrices,
    refetchInterval: 30000,
  })

  const priceMap = new Map((prices || []).map((p: TokenPrice) => [p.symbol, p]))

  const totalBalanceRub = (myBalances || []).reduce((sum: number, b: TokenBalance) => {
    const price = priceMap.get(b.tokenSymbol)?.price || 0
    return sum + (b.available + b.locked) * price
  }, 0)

  const activePositions = (myPositions || []).filter((p: Position) => p.isActive)

  if (loadingBalances) {
    return <div style={{ textAlign: 'center', padding: '80px 0' }}><Spin size="large" /></div>
  }

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <Title level={4} className="sber-page-title">Портфель</Title>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="Общий баланс" value={totalBalanceRub} icon={<WalletOutlined />}
            iconBg="#FEF3C7" iconColor="#F59E0B" formatter={formatRub} />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="Активные позиции" value={activePositions.length} icon={<PieChartOutlined />}
            iconBg="#F3E8FF" iconColor="#8B5CF6" />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="Незабранные комиссии" value={feeSummary?.totalUnclaimed ?? 0} icon={<DollarOutlined />}
            iconBg="#E0F2FE" iconColor="#0EA5E9" formatter={formatRub} />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard title="Всего заработано" value={feeSummary?.totalClaimed ?? 0} icon={<TrophyOutlined />}
            iconBg="#E8F5E9" iconColor="#21A038" formatter={formatRub} />
        </Col>
      </Row>

      {/* Token balances */}
      <Card className="sber-card" title={<Text strong>Мои токены</Text>}
        extra={<Button type="link" onClick={() => navigate('/swap')}>Обменять <ArrowRightOutlined /></Button>}>
        <Table
          className="sber-table"
          dataSource={myBalances || []}
          rowKey="tokenId"
          pagination={false}
          size="middle"
          columns={[
            {
              title: 'Токен',
              dataIndex: 'tokenSymbol',
              render: (sym: string, row: TokenBalance) => (
                <Space>
                  <div style={{
                    width: 32, height: 32, borderRadius: 16,
                    background: '#E8F5E9', display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontWeight: 700, fontSize: 12, color: '#21A038',
                  }}>
                    {sym.slice(0, 2)}
                  </div>
                  <div>
                    <Text strong>{sym}</Text>
                    <br />
                    <Text type="secondary" style={{ fontSize: 12 }}>{row.tokenName}</Text>
                  </div>
                </Space>
              ),
            },
            {
              title: 'Доступно',
              dataIndex: 'available',
              align: 'right' as const,
              render: (v: number) => v.toLocaleString('ru-RU', { maximumFractionDigits: 4 }),
            },
            {
              title: 'Заблокировано',
              dataIndex: 'locked',
              align: 'right' as const,
              render: (v: number) => v > 0 ? <Text type="warning">{v.toLocaleString('ru-RU', { maximumFractionDigits: 4 })}</Text> : '—',
            },
            {
              title: 'Цена',
              key: 'price',
              align: 'right' as const,
              render: (_: unknown, row: TokenBalance) => {
                const p = priceMap.get(row.tokenSymbol)
                return p ? `${p.price.toLocaleString('ru-RU', { maximumFractionDigits: 2 })} ₽` : '—'
              },
            },
            {
              title: 'Стоимость',
              key: 'value',
              align: 'right' as const,
              render: (_: unknown, row: TokenBalance) => {
                const p = priceMap.get(row.tokenSymbol)
                if (!p) return '—'
                const val = (row.available + row.locked) * p.price
                return <Text strong>{formatRub(val)}</Text>
              },
            },
          ]}
        />
      </Card>

      {/* Active positions */}
      {activePositions.length > 0 && (
        <Card className="sber-card" title={<Text strong>Активные позиции</Text>}
          extra={<Button type="link" onClick={() => navigate('/positions')}>Все позиции <ArrowRightOutlined /></Button>}>
          <Table
            className="sber-table"
            dataSource={activePositions.slice(0, 3)}
            rowKey="id"
            pagination={false}
            size="middle"
            columns={[
              {
                title: 'Пул',
                key: 'pool',
                render: (_: unknown, r: Position) => <Text strong>{r.tokenXSymbol}/{r.tokenYSymbol}</Text>,
              },
              {
                title: 'Стратегия',
                dataIndex: 'strategy',
                render: (s: string) => <Tag color="blue">{s}</Tag>,
              },
              {
                title: 'Диапазон бинов',
                key: 'range',
                render: (_: unknown, r: Position) => `${r.binRangeMin} — ${r.binRangeMax}`,
              },
              {
                title: 'Незабранные комиссии',
                key: 'fees',
                align: 'right' as const,
                render: (_: unknown, r: Position) => (
                  <Text type="success">
                    {r.unclaimedFeeX > 0 && `X: ${r.unclaimedFeeX.toLocaleString('ru-RU')} `}
                    {r.unclaimedFeeY > 0 && `Y: ${r.unclaimedFeeY.toLocaleString('ru-RU')}`}
                    {r.unclaimedFeeX === 0 && r.unclaimedFeeY === 0 && '—'}
                  </Text>
                ),
              },
            ]}
            onRow={(record) => ({
              style: { cursor: 'pointer' },
              onClick: () => navigate(`/pools/${record.poolId}`),
            })}
          />
        </Card>
      )}

      {/* Recent transactions */}
      <Card className="sber-card" title={<Text strong>Последние транзакции</Text>}
        extra={<Button type="link" onClick={() => navigate('/transactions')}>Вся история <ArrowRightOutlined /></Button>}>
        {!recentTx?.content?.length ? (
          <Text type="secondary">Транзакций пока нет</Text>
        ) : (
          <Table
            className="sber-table"
            dataSource={recentTx.content}
            rowKey="id"
            pagination={false}
            size="middle"
            columns={[
              {
                title: 'Дата',
                dataIndex: 'createdAt',
                render: (d: string) => dayjs(d).format('DD.MM.YYYY HH:mm'),
              },
              {
                title: 'Тип',
                dataIndex: 'txType',
                render: (t: string) => {
                  const cfg = txTypeLabels[t] || { text: t, color: 'default' }
                  return <Tag color={cfg.color}>{cfg.text}</Tag>
                },
              },
              {
                title: 'Сумма',
                key: 'amount',
                align: 'right' as const,
                render: (_: unknown, r: Transaction) => (
                  <Text>{r.amountIn?.toLocaleString('ru-RU') ?? '—'}</Text>
                ),
              },
              {
                title: 'Статус',
                dataIndex: 'status',
                render: (s: string) => {
                  const cfg = statusLabels[s] || { text: s, color: 'default' }
                  return <Tag color={cfg.color}>{cfg.text}</Tag>
                },
              },
            ]}
          />
        )}
      </Card>
    </Space>
  )
}
