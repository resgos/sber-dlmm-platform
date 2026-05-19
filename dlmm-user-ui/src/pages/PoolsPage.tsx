import { useState } from 'react'
import { Card, Row, Col, Tag, Typography, Space, Button, Input, Empty, Pagination, Skeleton } from 'antd'
import { SearchOutlined, ArrowRightOutlined, ThunderboltFilled } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { pools } from '@/api/services'
import type { Pool } from '@/api/types'
import { formatRub } from '@/components/StatCard'
import { pairAccent } from '@/components/TokenChip'
import { bpsToPercent } from '@/utils/format'

const { Title, Text } = Typography

const statusColors: Record<string, string> = {
  ACTIVE: 'success',
  PAUSED: 'warning',
  SHUTDOWN: 'error',
  PENDING: 'processing',
}

// Sprint 8 C-4 (rest) — labels resolved at render via t('pools.status.<KEY>').

// Sprint 7 dedup — pairAccent extracted to @/components/TokenChip (was
// duplicated here AND in SwapPage). PoolsPage uses just the function for
// PoolCard gradient backgrounds; SwapPage uses the full TokenChip component.

function PoolCard({ pool, onOpen, onAddLiquidity }: {
  pool: Pool
  onOpen: () => void
  onAddLiquidity: () => void
}) {
  const { t } = useTranslation()
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
            <Text type="secondary" style={{ fontSize: 12 }}>
              {t('pools.card.binStep', { value: bpsToPercent(pool.binStep) })} · {t('pools.card.fee', { value: bpsToPercent(pool.baseFeeBps) })}
            </Text>
          </div>
        </div>
        <Tag color={statusColors[pool.status] || 'default'} style={{ borderRadius: 999, padding: '2px 10px' }}>
          {t(`pools.status.${pool.status}`, { defaultValue: pool.status })}
        </Tag>
      </div>

      <div className="sber-pool-card__metrics">
        <div className="sber-pool-metric">
          <div className="sber-pool-metric__label">{t('pools.card.tvl')}</div>
          <div className="sber-pool-metric__value">{formatRub(tvl)}</div>
        </div>
        <div className="sber-pool-metric">
          <div className="sber-pool-metric__label">{t('pools.card.volume24h')}</div>
          <div className="sber-pool-metric__value">{formatRub(pool.volume24h ?? 0)}</div>
        </div>
        <div className="sber-pool-metric">
          <div className="sber-pool-metric__label">{t('pools.card.apy')}</div>
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
          {t('pools.card.addLiquidity')}
        </Button>
        <Button
          type="text"
          size="middle"
          onClick={(e) => { e.stopPropagation(); onOpen() }}
          style={{ color: 'var(--text-secondary)' }}
        >
          {t('common.details')} <ArrowRightOutlined />
        </Button>
      </div>
    </Card>
  )
}

export default function PoolsPage() {
  const { t } = useTranslation()
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
          <Title level={4} className="sber-page-title">{t('pools.title')}</Title>
          <Text type="secondary">{t('pools.subtitle', { count: filtered.length })}</Text>
        </div>
        <Input
          allowClear
          prefix={<SearchOutlined style={{ color: '#9CA3AF' }} />}
          placeholder={t('pools.searchPlaceholder')}
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
        <Empty description={search ? t('pools.empty.noResults', { query: search }) : t('pools.empty.noPools')} />
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
