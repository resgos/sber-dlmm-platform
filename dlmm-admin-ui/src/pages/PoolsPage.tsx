import { useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import {
  Table,
  Space,
  Tag,
  Typography,
  Card,
  Button,
  Select,
  TablePaginationConfig,
} from 'antd'
import {
  PlusOutlined,
  FilterOutlined,
  DollarOutlined,
  RiseOutlined,
  PercentageOutlined,
  AppstoreOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import { pools as poolService } from '@/api/services'
import type { Pool, PoolStatus } from '@/api/types'
import { bpsToPercent } from '@/utils/format'
import { KpiRow, PageHeader, TokenPairChip } from '@/components/sber'
import { formatCompact, formatRub } from '@/lib/format'

const { Text } = Typography

// Sprint 9 #M-5 — sort options consumed via URL ?sort= (set by Dashboard
// drill-down per #M-4). Each maps to a Pool field name; "none" returns
// data in API order (page-default).
type SortKey = 'none' | 'tvl' | 'volume24h' | 'apy'
const SORT_LABELS: Record<SortKey, string> = {
  none: 'По умолчанию',
  tvl: 'TVL (по убыванию)',
  volume24h: 'Объём 24ч (по убыванию)',
  apy: 'APY (по убыванию)',
}

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

export default function PoolsPage() {
  const navigate = useNavigate()
  // Sprint 9 #M-5 — URL-driven filter+sort. Status comes from Dashboard
  // drill-down (?status=ACTIVE → "Активные пулы" tile); sort from
  // ?sort=tvl / volume24h / apy (Dashboard TVL/Volume tiles). URL is
  // the source of truth so the user can bookmark a filtered view.
  const [searchParams, setSearchParams] = useSearchParams()
  const statusFilter = (searchParams.get('status') as PoolStatus | null) ?? null
  const sortKey: SortKey = (searchParams.get('sort') as SortKey | null) ?? 'none'

  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)

  const { data, isLoading } = useQuery({
    queryKey: ['pools', page, pageSize],
    queryFn: () => poolService.getPools(page, pageSize),
  })

  // Client-side filter+sort. Backend pagination still fetches a full page;
  // when the catalog grows past a few thousand pools, swap this for
  // server-side ?status=&sort= params. Today (22 pools) client filter is
  // fine and avoids a backend API change for this iteration.
  const filteredPools = useMemo(() => {
    const all = data?.content ?? []
    const filtered = statusFilter ? all.filter((p) => p.status === statusFilter) : all
    if (sortKey === 'none') return filtered
    const sorted = [...filtered]
    sorted.sort((a, b) => {
      switch (sortKey) {
        case 'tvl':
          return ((b.totalTvlX ?? 0) + (b.totalTvlY ?? 0)) - ((a.totalTvlX ?? 0) + (a.totalTvlY ?? 0))
        case 'volume24h':
          return (b.volume24h ?? 0) - (a.volume24h ?? 0)
        case 'apy':
          return (b.estimatedApy ?? 0) - (a.estimatedApy ?? 0)
        default:
          return 0
      }
    })
    return sorted
  }, [data, statusFilter, sortKey])

  const updateParam = (key: string, value: string | null) => {
    const next = new URLSearchParams(searchParams)
    if (value === null || value === '') next.delete(key)
    else next.set(key, value)
    setSearchParams(next, { replace: true })
    setPage(0)
  }

  // Sprint 9 (post-DS) — aggregates over the currently-loaded page.
  // Backend doesn't yet expose a /pools/aggregate endpoint; client-side
  // aggregation matches what the operator sees in the table below.
  const allPools = data?.content ?? []
  const totalTvl = allPools.reduce(
    (acc, p) => acc + (p.totalTvlX ?? 0) + (p.totalTvlY ?? 0),
    0,
  )
  const totalVolume24h = allPools.reduce((acc, p) => acc + (p.volume24h ?? 0), 0)
  const activeCount = allPools.filter((p) => p.status === 'ACTIVE').length
  const apyVals = allPools.map((p) => p.estimatedApy ?? 0).filter((v) => v > 0)
  const avgApy = apyVals.length > 0 ? apyVals.reduce((a, b) => a + b, 0) / apyVals.length : 0

  const columns: ColumnsType<Pool> = [
    {
      title: 'Пара',
      key: 'pair',
      width: 200,
      render: (_, record) => (
        <TokenPairChip x={record.tokenXSymbol} y={record.tokenYSymbol} />
      ),
    },
    {
      title: 'Шаг бина',
      dataIndex: 'binStep',
      key: 'binStep',
      align: 'right',
      render: (val: number) => bpsToPercent(val),
    },
    {
      title: 'Базовая комиссия',
      dataIndex: 'baseFeeBps',
      key: 'baseFeeBps',
      align: 'right',
      render: (val: number) => bpsToPercent(val),
    },
    {
      title: 'Текущая цена',
      dataIndex: 'currentPrice',
      key: 'currentPrice',
      align: 'right',
      render: (val: number) => val.toLocaleString('ru-RU', { maximumFractionDigits: 6 }),
    },
    {
      // Sprint 9 — column title is dynamic per-row via render rather than
      // a fixed "(X)". Header stays generic; cell value is suffixed with
      // the actual symbol from that row. Sprint 9-DS — switched from raw
      // toLocaleString (which gave us "1 944 006 221 000") to formatCompact
      // ("1.94 трлн") for readability.
      title: 'Резерв X',
      dataIndex: 'totalTvlX',
      key: 'totalTvlX',
      align: 'right',
      render: (val: number, row: Pool) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatCompact(val)}
          <span style={{ color: 'var(--text-muted)', fontSize: 11, marginLeft: 6 }}>
            {row.tokenXSymbol}
          </span>
        </span>
      ),
    },
    {
      title: 'Резерв Y',
      dataIndex: 'totalTvlY',
      key: 'totalTvlY',
      align: 'right',
      render: (val: number, row: Pool) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>
          {formatCompact(val)}
          <span style={{ color: 'var(--text-muted)', fontSize: 11, marginLeft: 6 }}>
            {row.tokenYSymbol}
          </span>
        </span>
      ),
    },
    {
      title: 'Объём 24ч',
      dataIndex: 'volume24h',
      key: 'volume24h',
      align: 'right',
      render: (val: number) => (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>{formatCompact(val)}</span>
      ),
    },
    {
      title: 'Расч. APY',
      dataIndex: 'estimatedApy',
      key: 'estimatedApy',
      align: 'right',
      render: (val: number) => (
        <span style={{ color: val > 0 ? '#21A038' : undefined, fontWeight: 500 }}>
          {val.toFixed(2)}%
        </span>
      ),
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      render: (status: PoolStatus) => (
        <Tag color={poolStatusColor[status]}>{poolStatusLabel[status] || status}</Tag>
      ),
    },
  ]

  const handleTableChange = (pagination: TablePaginationConfig) => {
    setPage((pagination.current ?? 1) - 1)
    setPageSize(pagination.pageSize ?? 20)
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <PageHeader
        title="Пулы ликвидности"
        subtitle="Бин-ориентированные AMM-пулы платформы — резервы, объёмы, доходность"
        actions={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/pools/create')}
            style={{ borderRadius: 8 }}
          >
            Создать пул
          </Button>
        }
      />

      <KpiRow
        tiles={[
          {
            label: 'Активных пулов',
            value: `${activeCount} / ${allPools.length}`,
            sub: `доступно для торговли`,
            icon: <AppstoreOutlined style={{ color: 'var(--sber-green)' }} />,
          },
          {
            label: 'Совокупный TVL',
            value: formatRub(totalTvl),
            sub: 'сумма резервов обоих токенов',
            icon: <DollarOutlined style={{ color: '#F2994A' }} />,
          },
          {
            label: 'Объём за 24ч',
            value: formatCompact(totalVolume24h),
            sub: 'свопов на платформе',
            icon: <RiseOutlined style={{ color: '#296AE3' }} />,
          },
          {
            label: 'Средний APY',
            value: avgApy > 0 ? `${avgApy.toFixed(2)}%` : '—',
            sub: avgApy > 0 ? 'по пулам с фактическим объёмом' : 'недостаточно данных',
            icon: <PercentageOutlined style={{ color: '#9B59B6' }} />,
            accent: avgApy > 0 ? 'var(--sber-green)' : undefined,
          },
        ]}
      />


      {/* Sprint 9 #M-5 — filter+sort row. URL-bound so Dashboard #M-4
          tile drill-downs (?status=ACTIVE / ?sort=tvl) reach the right
          state automatically. */}
      <Card
        className="sber-card"
        style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
        styles={{ body: { padding: '12px 16px' } }}
      >
        <Space size={16} wrap>
          <Space size={6}>
            <FilterOutlined aria-hidden style={{ color: 'var(--text-secondary)' }} />
            <Text strong style={{ fontSize: 13 }}>Статус:</Text>
            <Select<PoolStatus | 'ALL'>
              size="middle"
              style={{ minWidth: 160 }}
              value={(statusFilter ?? 'ALL') as PoolStatus | 'ALL'}
              onChange={(v) => updateParam('status', v === 'ALL' ? null : v)}
              aria-label="Фильтр по статусу пула"
              options={[
                { value: 'ALL', label: 'Все' },
                { value: 'ACTIVE', label: poolStatusLabel.ACTIVE },
                { value: 'PAUSED', label: poolStatusLabel.PAUSED },
                { value: 'SHUTDOWN', label: poolStatusLabel.SHUTDOWN },
                { value: 'PENDING', label: poolStatusLabel.PENDING },
              ]}
            />
          </Space>
          <Space size={6}>
            <Text strong style={{ fontSize: 13 }}>Сортировка:</Text>
            <Select<SortKey>
              size="middle"
              style={{ minWidth: 220 }}
              value={sortKey}
              onChange={(v) => updateParam('sort', v === 'none' ? null : v)}
              aria-label="Сортировка пулов"
              options={(Object.keys(SORT_LABELS) as SortKey[]).map((k) => ({
                value: k,
                label: SORT_LABELS[k],
              }))}
            />
          </Space>
          {(statusFilter || sortKey !== 'none') && (
            <Button
              type="link"
              size="small"
              onClick={() => setSearchParams({}, { replace: true })}
            >
              Сбросить
            </Button>
          )}
          <Text type="secondary" style={{ marginLeft: 'auto', fontSize: 12 }}>
            {filteredPools.length} {data?.totalElements ? `из ${data.totalElements}` : ''}
          </Text>
        </Space>
      </Card>

      <Card className="sber-card sber-table" style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}>
        <Table<Pool>
          columns={columns}
          dataSource={filteredPools}
          rowKey="id"
          loading={isLoading}
          pagination={{
            current: page + 1,
            pageSize,
            total: filteredPools.length,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (total) => `Всего ${total} пулов`,
          }}
          onChange={handleTableChange}
          onRow={(record) => ({
            onClick: () => navigate(`/pools/${record.id}`),
            style: { cursor: 'pointer' },
          })}
          size="middle"
          scroll={{ x: 1000 }}
        />
      </Card>
    </Space>
  )
}
