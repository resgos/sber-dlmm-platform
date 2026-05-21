import { Card, Typography, Space, Tag, Empty, Spin, Tooltip } from 'antd'
import { HistoryOutlined, ArrowRightOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { transactions } from '@/api/services'
import type { Pool, Transaction } from '@/api/types'
import { formatCompact } from '@/lib/format'
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/ru'

dayjs.extend(relativeTime)
dayjs.locale('ru')

const { Text } = Typography

/**
 * Sprint 9-DS-r4 (P1-6) — Meteora-style pool-scoped recent SWAP feed.
 *
 * <p>Sits below the bin chart + "Мои позиции" on PoolDetailPage and
 * mirrors Meteora's "History" tab at the bottom of the pool page:
 * the last N swaps for THIS pool only, newest first, with direction
 * (X→Y vs Y→X) and human-readable amounts.
 *
 * <p>Backend: GET /api/v1/transactions/pool/{poolId}?limit=N (added
 * in the same Sprint 9-DS-r4 P1-6 change). Refresh interval matches
 * the rest of the page (no auto-poll — the user can navigate away
 * and back, or perform a swap which invalidates this query).
 */
interface PoolRecentSwapsPanelProps {
  pool: Pool
  limit?: number
}

export default function PoolRecentSwapsPanel({ pool, limit = 20 }: PoolRecentSwapsPanelProps) {
  const { data, isLoading } = useQuery<Transaction[]>({
    queryKey: ['poolRecentSwaps', pool.id, limit],
    queryFn: () => transactions.getRecentPoolTransactions(pool.id, limit),
    // Refresh when the page returns to focus — captures other users'
    // swaps without burning bandwidth while the user is idle. Server
    // /actuator/health budgets prefer this over a setInterval.
    refetchOnWindowFocus: true,
    staleTime: 15_000,
  })

  return (
    <Card
      className="sber-card"
      style={{ borderRadius: 12, border: '1px solid var(--border-light)', marginTop: 16 }}
      title={
        <Space size={8}>
          <HistoryOutlined style={{ color: 'var(--sber-green)' }} />
          <Text strong>Последние обмены</Text>
          {data && data.length > 0 && (
            <Tag style={{ borderRadius: 999, fontSize: 11 }}>{data.length}</Tag>
          )}
        </Space>
      }
      extra={
        <Text type="secondary" style={{ fontSize: 11 }}>
          Только этот пул
        </Text>
      }
      styles={{ body: { padding: 0 } }}
    >
      {isLoading ? (
        <div style={{ padding: 32, textAlign: 'center' }}>
          <Spin />
        </div>
      ) : !data || data.length === 0 ? (
        <Empty
          description="Пока нет обменов"
          imageStyle={{ height: 48 }}
          style={{ padding: '24px 0' }}
        />
      ) : (
        <div style={{ maxHeight: 360, overflowY: 'auto' }}>
          {data.map((t, i) => (
            <SwapRow
              key={t.id}
              tx={t}
              pool={pool}
              isFirst={i === 0}
            />
          ))}
        </div>
      )}
    </Card>
  )
}

function SwapRow({
  tx,
  pool,
  isFirst,
}: {
  tx: Transaction
  pool: Pool
  isFirst: boolean
}) {
  // Direction: tokenInId === pool.tokenXId means user gave X, received Y.
  const xToY = tx.tokenInId === pool.tokenXId
  const inSym = xToY ? pool.tokenXSymbol : pool.tokenYSymbol
  const outSym = xToY ? pool.tokenYSymbol : pool.tokenXSymbol
  const inColor = xToY ? '#1677FF' : 'var(--sber-green)'
  const outColor = xToY ? 'var(--sber-green)' : '#1677FF'

  const when = tx.createdAt ? dayjs(tx.createdAt) : null
  const whenAbs = when ? when.format('DD.MM.YYYY HH:mm:ss') : '—'
  const whenRel = when ? when.fromNow() : '—'

  return (
    <div
      style={{
        padding: '10px 16px',
        borderTop: isFirst ? 'none' : '1px solid var(--border-light)',
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        flexWrap: 'wrap',
      }}
    >
      <div style={{ minWidth: 0, flex: '1 1 200px', display: 'flex', alignItems: 'center', gap: 8 }}>
        <Tag
          color={xToY ? 'blue' : 'green'}
          style={{ borderRadius: 999, padding: '1px 8px', margin: 0, fontSize: 10, fontWeight: 600 }}
        >
          {xToY ? 'X→Y' : 'Y→X'}
        </Tag>
        <div style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12, color: 'var(--text-secondary)' }}>
          {tx.id.substring(0, 8)}
        </div>
      </div>

      <div
        style={{
          flex: '2 1 320px',
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          fontVariantNumeric: 'tabular-nums',
          fontSize: 13,
        }}
      >
        <span style={{ color: inColor, fontWeight: 600 }}>
          {formatCompact(tx.amountIn ?? 0)} {inSym}
        </span>
        <ArrowRightOutlined style={{ color: 'var(--text-muted)', fontSize: 11 }} />
        <span style={{ color: outColor, fontWeight: 600 }}>
          {formatCompact(tx.amountOut ?? 0)} {outSym}
        </span>
      </div>

      <Tooltip title={whenAbs}>
        <div style={{ flex: '0 0 auto', fontSize: 11, color: 'var(--text-muted)' }}>
          {whenRel}
        </div>
      </Tooltip>
    </div>
  )
}
