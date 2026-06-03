import { useState } from 'react'
import { Card, Row, Col, Tag, Typography, Space, Button, Input, Pagination, Skeleton, Segmented } from 'antd'
import EmptyState from '@/components/EmptyState'
import { SearchOutlined, ArrowRightOutlined, ThunderboltFilled, BarChartOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { pools } from '@/api/services'
import type { Pool } from '@/api/types'
import { formatRub } from '@/components/StatCard'
import TokenIcon from '@/components/TokenIcon'
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
  const tvl = (pool.totalTvlX ?? 0) + (pool.totalTvlY ?? 0)
  const apy = pool.estimatedApy ?? 0
  // Meteora-style — 24h fees earned ≈ volume × base fee rate. A quick yield
  // signal next to TVL/volume/APY without any new backend call.
  const fees24h = Math.round(((pool.volume24h ?? 0) * (pool.baseFeeBps ?? 0)) / 10_000)

  return (
    <Card className="sber-pool-card" hoverable onClick={onOpen}>
      <div className="sber-pool-card__head">
        <div className="sber-pool-pair">
          <span style={{ display: 'inline-flex', alignItems: 'center' }}>
            <TokenIcon symbol={x} size={44} decorative />
            <span style={{ display: 'inline-flex', marginLeft: -16, borderRadius: '50%', boxShadow: '0 0 0 3px var(--bg-card)' }}>
              <TokenIcon symbol={y} size={44} decorative />
            </span>
          </span>
          <div className="sber-pool-pair__label">
            <Text strong style={{ fontSize: 'var(--text-md)' }}>{x}/{y}</Text>
            <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
              {t('pools.card.binStep', { value: bpsToPercent(pool.binStep) })} · {t('pools.card.fee', { value: bpsToPercent(pool.baseFeeBps) })}
            </Text>
          </div>
        </div>
        <Tag color={statusColors[pool.status] || 'default'} style={{ borderRadius: 'var(--radius-pill)', padding: '2px 10px' }}>
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
          <div className="sber-pool-metric__label">{t('pools.card.fees24h')}</div>
          <div className="sber-pool-metric__value">{formatRub(fees24h)}</div>
        </div>
        <div className="sber-pool-metric">
          <div className="sber-pool-metric__label">{t('pools.card.apy')}</div>
          <div className="sber-pool-metric__value sber-pool-metric__value--accent">
            <ThunderboltFilled style={{ fontSize: 'var(--text-xs)', marginRight: 4 }} />
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

  // Sprint 15 perf — staleTime=30s eliminates the refetch storm when user
  // navigates Pools → Detail → back. Pool listings are slow-changing
  // (status + TVL update every 10s server-side); 30s client cache is well
  // within product tolerance.
  //
  // Pagination is CLIENT-SIDE (filter + sort + slice below), so the catalogue is
  // fetched ONCE under a FIXED key — `page` must NOT be threaded into the backend
  // request. The old `getPools(page, pageSize*4)` asked the backend for page N
  // (size 48); opening client page 2 therefore requested an out-of-range backend
  // page for a 22-pool catalogue, which came back empty → the 2nd page went
  // blank. Fetch page 0 / size 100 (covers the demo with headroom and shares the
  // ['pools',0,100] cache with the Positions/Transactions catalogue join).
  // Server-side paging is a future concern only if the catalogue exceeds 100.
  const { data, isLoading } = useQuery({
    queryKey: ['pools', 0, 100],
    queryFn: () => pools.getPools(0, 100),
    staleTime: 30_000,
  })

  // Batch #6 unit 5 — sort options. Default sort by Volume24h DESC
  // (most active first) — institutional users пришли смотреть «где
  // деньги торгуются», не алфавитный список. TVL и APY tabs для
  // других use cases.
  const [sortBy, setSortBy] = useState<'volume24h' | 'tvl' | 'apy' | 'name'>('volume24h')

  const allPools: Pool[] = data?.content || []
  const filtered = search.trim()
    ? allPools.filter((p) => {
        const q = search.trim().toUpperCase()
        return (p.tokenXSymbol || '').includes(q) || (p.tokenYSymbol || '').includes(q)
      })
    : allPools
  const sorted = [...filtered].sort((a, b) => {
    switch (sortBy) {
      case 'volume24h': return (b.volume24h ?? 0) - (a.volume24h ?? 0)
      case 'tvl': return ((b.totalTvlX ?? 0) + (b.totalTvlY ?? 0)) - ((a.totalTvlX ?? 0) + (a.totalTvlY ?? 0))
      case 'apy': return (b.estimatedApy ?? 0) - (a.estimatedApy ?? 0)
      case 'name': return (a.tokenXSymbol || '').localeCompare(b.tokenXSymbol || '')
      default: return 0
    }
  })
  const pageStart = page * pageSize
  const pagePools = sorted.slice(pageStart, pageStart + pageSize)

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <div>
          <Title level={4} className="sber-page-title">{t('pools.title')}</Title>
          <Text type="secondary">{t('pools.subtitle', { count: filtered.length })}</Text>
        </div>
        <Space size={12} wrap>
          <Input
            allowClear
            prefix={<SearchOutlined style={{ color: '#9CA3AF' }} />}
            placeholder={t('pools.searchPlaceholder')}
            value={search}
            onChange={(e) => { setSearch(e.target.value); setPage(0) }}
            // Sprint 9-DS-r4 P2-16 — fixed 320px width pushed past 320 viewport;
            // class drops to width:100% under .sber-pools-search-mobile media
            // query in sber-theme.css.
            className="sber-pools-search"
            style={{ height: 40, borderRadius: 'var(--radius-sm)' }}
          />
          {/* Batch #6 unit 5 — sort selector. */}
          <Segmented
            value={sortBy}
            onChange={(v) => setSortBy(v as typeof sortBy)}
            options={[
              { label: t('pools.sort.volume'), value: 'volume24h' },
              { label: t('pools.sort.tvl'), value: 'tvl' },
              { label: t('pools.sort.apy'), value: 'apy' },
              { label: t('pools.sort.name'), value: 'name' },
            ]}
          />
          {/* Sprint 10 (new feature) — pool comparator entry point. */}
          <Button
            icon={<BarChartOutlined />}
            onClick={() => navigate('/pools/compare')}
            style={{ height: 40, borderRadius: 'var(--radius-sm)' }}
          >
            {t('pools.compareButton')}
          </Button>
        </Space>
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
        <EmptyState
          title={search ? t('pools.empty.noResults', { query: search }) : t('pools.empty.noPools')}
          description={search ? t('pools.empty.noResultsHint') : undefined}
        />
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
