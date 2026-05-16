import { useState } from 'react'
import { Card, Row, Col, Tag, Typography, Space, Button, Input, Empty, Pagination, Skeleton } from 'antd'
import { SearchOutlined, ArrowRightOutlined, ThunderboltFilled } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { pools } from '@/api/services'
import type { Pool } from '@/api/types'
import { formatRub } from '@/components/StatCard'

const { Title, Text } = Typography

const statusColors: Record<string, string> = {
  ACTIVE: 'success',
  PAUSED: 'warning',
  SHUTDOWN: 'error',
  PENDING: 'processing',
}

const statusLabels: Record<string, string> = {
  ACTIVE: 'Активен',
  PAUSED: 'Пауза',
  SHUTDOWN: 'Остановлен',
  PENDING: 'Ожидание',
}

// Deterministic accent colour per token pair — gives every card a unique
// visual identity without needing real per-token branding assets.
function pairAccent(symbol: string): { from: string; to: string } {
  const palette: Array<{ from: string; to: string }> = [
    { from: '#21A038', to: '#00C853' },  // sber green
    { from: '#00B5A1', to: '#21A038' },  // aqua/green
    { from: '#6E5BFF', to: '#00B5A1' },  // violet/aqua
    { from: '#FFB320', to: '#FF6F61' },  // amber/coral
    { from: '#0EA5E9', to: '#6E5BFF' },  // blue/violet
    { from: '#21A038', to: '#FFB320' },  // green/amber
    { from: '#FF6F61', to: '#6E5BFF' },  // coral/violet
    { from: '#00C853', to: '#0EA5E9' },  // green/blue
  ]
  let hash = 0
  for (let i = 0; i < symbol.length; i++) hash = (hash * 31 + symbol.charCodeAt(i)) >>> 0
  return palette[hash % palette.length]
}

function PoolCard({ pool, onOpen, onAddLiquidity }: {
  pool: Pool
  onOpen: () => void
  onAddLiquidity: () => void
}) {
  const x = pool.tokenXSymbol || '???'
  const y = pool.tokenYSymbol || '???'
  const accentX = pairAccent(x)
  const accentY = pairAccent(y)
  const tvl = (pool.totalTvlX ?? 0) + (pool.totalTvlY ?? 0)
  const apy = pool.estimatedApy ?? 0

  return (
    <Card className="sber-pool-card" hoverable onClick={onOpen}>
      <div className="sber-pool-card__head">
        <div className="sber-pool-pair">
          <div className="sber-token-chip" style={{ background: `linear-gradient(135deg, ${accentX.from}, ${accentX.to})` }}>
            {x.slice(0, 4)}
          </div>
          <div className="sber-token-chip sber-token-chip--overlap" style={{ background: `linear-gradient(135deg, ${accentY.from}, ${accentY.to})` }}>
            {y.slice(0, 4)}
          </div>
          <div className="sber-pool-pair__label">
            <Text strong style={{ fontSize: 15 }}>{x}/{y}</Text>
            <Text type="secondary" style={{ fontSize: 12 }}>bin step {pool.binStep} · fee {pool.baseFeeBps} bps</Text>
          </div>
        </div>
        <Tag color={statusColors[pool.status] || 'default'} style={{ borderRadius: 999, padding: '2px 10px' }}>
          {statusLabels[pool.status] || pool.status}
        </Tag>
      </div>

      <div className="sber-pool-card__metrics">
        <div className="sber-pool-metric">
          <div className="sber-pool-metric__label">TVL</div>
          <div className="sber-pool-metric__value">{formatRub(tvl)}</div>
        </div>
        <div className="sber-pool-metric">
          <div className="sber-pool-metric__label">Объём 24ч</div>
          <div className="sber-pool-metric__value">{formatRub(pool.volume24h ?? 0)}</div>
        </div>
        <div className="sber-pool-metric">
          <div className="sber-pool-metric__label">APY</div>
          <div className="sber-pool-metric__value sber-pool-metric__value--accent">
            <ThunderboltFilled style={{ fontSize: 12, marginRight: 4 }} />
            {apy.toFixed(2)}%
          </div>
        </div>
      </div>

      <div className="sber-pool-card__cta">
        <Button
          type="primary"
          size="middle"
          block
          onClick={(e) => { e.stopPropagation(); onAddLiquidity() }}
        >
          Добавить ликвидность
        </Button>
        <Button
          type="text"
          size="middle"
          onClick={(e) => { e.stopPropagation(); onOpen() }}
          style={{ color: 'var(--text-secondary)' }}
        >
          Подробнее <ArrowRightOutlined />
        </Button>
      </div>
    </Card>
  )
}

export default function PoolsPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const pageSize = 12

  const { data, isLoading } = useQuery({
    queryKey: ['pools', page],
    queryFn: () => pools.getPools(page, pageSize * 4), // grab a wider page; we filter client-side
  })

  const allPools: Pool[] = data?.content || []
  const filtered = search.trim()
    ? allPools.filter((p) => {
        const q = search.trim().toUpperCase()
        return (p.tokenXSymbol || '').includes(q) || (p.tokenYSymbol || '').includes(q)
      })
    : allPools
  const pageStart = page * pageSize
  const pagePools = filtered.slice(pageStart, pageStart + pageSize)

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <div>
          <Title level={4} className="sber-page-title">Пулы ликвидности</Title>
          <Text type="secondary">{filtered.length} пар доступны для торговли и предоставления ликвидности</Text>
        </div>
        <Input
          allowClear
          prefix={<SearchOutlined style={{ color: '#9CA3AF' }} />}
          placeholder="Найти пару (SBER, GAZP, SRUB…)"
          value={search}
          onChange={(e) => { setSearch(e.target.value); setPage(0) }}
          style={{ width: 320, height: 40, borderRadius: 10 }}
        />
      </div>

      {isLoading ? (
        <Row gutter={[16, 16]}>
          {[1, 2, 3, 4, 5, 6].map((i) => (
            <Col key={i} xs={24} sm={12} lg={8} xxl={6}>
              <Card className="sber-pool-card"><Skeleton active paragraph={{ rows: 3 }} /></Card>
            </Col>
          ))}
        </Row>
      ) : pagePools.length === 0 ? (
        <Empty description={search ? `Пары "${search}" не найдены` : 'Пулы пока не созданы'} />
      ) : (
        <>
          <Row gutter={[16, 16]}>
            {pagePools.map((p) => (
              <Col key={p.id} xs={24} sm={12} lg={8} xxl={6}>
                <PoolCard
                  pool={p}
                  onOpen={() => navigate(`/pools/${p.id}`)}
                  onAddLiquidity={() => navigate(`/pools/${p.id}/liquidity`)}
                />
              </Col>
            ))}
          </Row>
          <div style={{ display: 'flex', justifyContent: 'center' }}>
            <Pagination
              current={page + 1}
              pageSize={pageSize}
              total={filtered.length}
              showSizeChanger={false}
              onChange={(p) => setPage(p - 1)}
            />
          </div>
        </>
      )}
    </Space>
  )
}
