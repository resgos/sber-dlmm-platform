import { useMemo, useState } from 'react'
import { Table, Tag, Typography, Space, Button, Card, Modal, Slider, message, Row, Col } from 'antd'
import { DollarOutlined, DeleteOutlined, PieChartOutlined, TrophyOutlined, WalletOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { pools, fees } from '@/api/services'
import type { Position, Pool, FeeHistoryEntry } from '@/api/types'
import { KpiRow, PageHeader, TokenPairChip } from '@/components/sber'
import { formatCompact, formatRub, formatTokenAmount } from '@/lib/format'
import dayjs from 'dayjs'

const { Text } = Typography

// Sprint 9 — DLMM bins are evenly-spaced on a log scale: price at binId =
// basePrice * (1 + binStepBps/10000)^(binId - activeBinId). We render the
// raw bin range as a human-readable price range (in tokenY per 1 tokenX)
// because nobody outside the engineering team can mentally translate
// "8388603 — 8388613" into a price range. Falls back to the raw numbers
// if we don't have the surrounding pool's current price yet (first paint).
function formatBinPriceRange(
  binMin: number,
  binMax: number,
  activeBinId: number | null | undefined,
  binStepBps: number | null | undefined,
  currentPrice: number | null | undefined,
  quoteSymbol: string,
): string {
  if (!activeBinId || !binStepBps || !currentPrice) {
    return `${binMin} — ${binMax}`
  }
  const r = 1 + binStepBps / 10000
  const lo = currentPrice * Math.pow(r, binMin - activeBinId)
  const hi = currentPrice * Math.pow(r, binMax - activeBinId)
  const fmt = (n: number) =>
    n.toLocaleString('ru-RU', { maximumFractionDigits: n >= 100 ? 2 : 4 })
  return `${fmt(lo)} — ${fmt(hi)} ${quoteSymbol}`
}

export default function PositionsPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [removeModalPos, setRemoveModalPos] = useState<Position | null>(null)
  const [removePercent, setRemovePercent] = useState(100)

  const { data: myPositions, isLoading } = useQuery({
    queryKey: ['myPositions'],
    queryFn: pools.getMyPositions,
  })

  // Sprint 9 — backend Position DTO doesn't carry tokenXSymbol /
  // tokenYSymbol / binStep / currentPrice / activeBinId, so the page
  // was rendering "/" for the pair and raw bin IDs for the range.
  // Pull the whole pool catalog (cheap, server-paginated) and join
  // by poolId on the client. The catalog is already cached by other
  // pages so this is usually a free read.
  const { data: poolPage } = useQuery({
    queryKey: ['pools', 0, 100],
    queryFn: () => pools.getPools(0, 100),
  })
  const poolById = useMemo(() => {
    const map = new Map<string, Pool>()
    for (const p of poolPage?.content ?? []) map.set(p.id, p)
    return map
  }, [poolPage])

  const { data: feeSummary } = useQuery({
    queryKey: ['myFeeSummary'],
    queryFn: fees.getMyFeeSummary,
  })

  const { data: feeHistory } = useQuery({
    queryKey: ['myFeeHistory'],
    queryFn: () => fees.getMyFeeHistory(0, 20),
  })

  const claimMutation = useMutation({
    mutationFn: (positionId: string) => fees.claimFees({ positionId }),
    onSuccess: () => {
      message.success('Комиссии забраны')
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myFeeSummary'] })
      queryClient.invalidateQueries({ queryKey: ['myFeeHistory'] })
    },
    onError: (err: any) => message.error(err?.response?.data?.message || 'Ошибка'),
  })

  const removeMutation = useMutation({
    mutationFn: (positionId: string) =>
      pools.removeLiquidity({
        positionId,
        percentage: removePercent,
        idempotencyKey: crypto.randomUUID(),
      }),
    onSuccess: () => {
      message.success('Ликвидность удалена')
      setRemoveModalPos(null)
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
    },
    onError: (err: any) => message.error(err?.response?.data?.message || 'Ошибка'),
  })

  const activePositions = (myPositions || []).filter((p: Position) => p.isActive)

  const totalUnclaimedX = activePositions.reduce((s: number, p: Position) => s + p.unclaimedFeeX, 0)
  const totalUnclaimedY = activePositions.reduce((s: number, p: Position) => s + p.unclaimedFeeY, 0)

  return (
    <Space direction="vertical" size={20} style={{ width: '100%' }}>
      <PageHeader
        title="Мои позиции"
        subtitle="Ваши LP-позиции в DLMM-пулах — диапазоны бинов, незабранные комиссии, история выплат"
      />

      <KpiRow
        tiles={[
          {
            label: 'Активных позиций',
            value: activePositions.length.toLocaleString('ru-RU'),
            sub: activePositions.length === 0 ? 'нет открытых' : 'предоставляют ликвидность',
            icon: <PieChartOutlined style={{ color: '#9B59B6' }} />,
          },
          {
            label: 'Незабранные комиссии',
            value: formatRub(feeSummary?.totalUnclaimed ?? 0),
            sub: 'готовы к claim',
            icon: <WalletOutlined style={{ color: '#296AE3' }} />,
            accent: (feeSummary?.totalUnclaimed ?? 0) > 0 ? 'var(--sber-green)' : undefined,
          },
          {
            label: 'Всего заработано',
            value: formatRub(feeSummary?.totalClaimed ?? 0),
            sub: 'за всё время',
            icon: <TrophyOutlined style={{ color: '#F2994A' }} />,
          },
        ]}
      />

      <Card className="sber-card" title={<Text strong>Позиции</Text>}>
        <Table
          className="sber-table"
          loading={isLoading}
          dataSource={activePositions}
          rowKey="id"
          pagination={false}
          size="middle"
          onRow={(record) => ({
            style: { cursor: 'pointer' },
            onClick: () => navigate(`/pools/${record.poolId}`),
          })}
          columns={[
            {
              title: 'Пул',
              key: 'pool',
              width: 220,
              render: (_: unknown, r: Position) => {
                const pool = poolById.get(r.poolId)
                if (pool) return <TokenPairChip x={pool.tokenXSymbol} y={pool.tokenYSymbol} />
                return <Text type="secondary">пул {r.poolId.slice(0, 6)}…</Text>
              },
            },
            { title: 'Стратегия', dataIndex: 'strategy', render: (s: string) => <Tag color="blue">{s}</Tag> },
            {
              title: 'Диапазон цен',
              key: 'range',
              render: (_: unknown, r: Position) => {
                const pool = poolById.get(r.poolId)
                return (
                  <span style={{ fontVariantNumeric: 'tabular-nums', fontSize: 13 }}>
                    {formatBinPriceRange(
                      r.binRangeMin,
                      r.binRangeMax,
                      pool?.activeBinId,
                      pool?.binStep,
                      pool?.currentPrice,
                      pool?.tokenYSymbol ?? '',
                    )}
                  </span>
                )
              },
            },
            {
              title: 'Незабранные комиссии',
              key: 'unclaimed',
              align: 'right' as const,
              render: (_: unknown, r: Position) => {
                // Sprint 9 — was two columns ("Незабранные X", "Незабранные Y")
                // with raw numbers and no unit. Now one column showing the
                // actual token symbols. Renders "—" only when BOTH legs
                // are zero so a user sees "+120M SUSDT" even when only
                // one side has accrued fees.
                const pool = poolById.get(r.poolId)
                const xSym = pool?.tokenXSymbol ?? 'X'
                const ySym = pool?.tokenYSymbol ?? 'Y'
                if (r.unclaimedFeeX === 0 && r.unclaimedFeeY === 0) {
                  return <Text type="secondary">—</Text>
                }
                return (
                  <Space size={6} wrap style={{ justifyContent: 'flex-end' }}>
                    {r.unclaimedFeeX > 0 && (
                      <Tag color="green" style={{ marginInlineEnd: 0, borderRadius: 999, padding: '0 8px', fontVariantNumeric: 'tabular-nums' }}>
                        +{formatTokenAmount(r.unclaimedFeeX, xSym, { compact: true, maxFractionDigits: 4 })}
                      </Tag>
                    )}
                    {r.unclaimedFeeY > 0 && (
                      <Tag color="green" style={{ marginInlineEnd: 0, borderRadius: 999, padding: '0 8px', fontVariantNumeric: 'tabular-nums' }}>
                        +{formatTokenAmount(r.unclaimedFeeY, ySym, { compact: true, maxFractionDigits: 4 })}
                      </Tag>
                    )}
                  </Space>
                )
              },
            },
            {
              title: 'Создана',
              dataIndex: 'createdAt',
              render: (d: string) => dayjs(d).format('DD.MM.YYYY'),
            },
            {
              title: 'Действия',
              key: 'actions',
              render: (_: unknown, r: Position) => (
                <Space onClick={(e) => e.stopPropagation()}>
                  <Button size="small" type="primary" ghost icon={<DollarOutlined />}
                    onClick={() => claimMutation.mutate(r.id)}
                    loading={claimMutation.isPending}
                    disabled={r.unclaimedFeeX === 0 && r.unclaimedFeeY === 0}>
                    Забрать
                  </Button>
                  <Button size="small" danger icon={<DeleteOutlined />}
                    onClick={() => { setRemoveModalPos(r); setRemovePercent(100) }}>
                    Удалить
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      {/* Fee history */}
      {feeHistory?.content && feeHistory.content.length > 0 && (
        <Card className="sber-card" title={<Text strong>История комиссий</Text>}>
          <Table
            className="sber-table"
            dataSource={feeHistory.content}
            rowKey="id"
            pagination={false}
            size="small"
            columns={[
              {
                title: 'Дата',
                dataIndex: 'accruedAt',
                render: (d: string) => dayjs(d).format('DD.MM.YYYY HH:mm'),
              },
              { title: 'Сумма', dataIndex: 'amount', align: 'right' as const, render: (v: number) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{formatCompact(v)}</span> },
              {
                title: 'Статус',
                dataIndex: 'claimed',
                render: (v: boolean) => <Tag color={v ? 'success' : 'processing'}>{v ? 'Забрано' : 'Начислено'}</Tag>,
              },
              {
                title: 'Дата выплаты',
                dataIndex: 'claimedAt',
                render: (d: string | null) => d ? dayjs(d).format('DD.MM.YYYY HH:mm') : '—',
              },
            ]}
          />
        </Card>
      )}

      <Modal
        title="Удаление ликвидности"
        open={!!removeModalPos}
        onCancel={() => setRemoveModalPos(null)}
        onOk={() => removeModalPos && removeMutation.mutate(removeModalPos.id)}
        confirmLoading={removeMutation.isPending}
        okText="Удалить"
        cancelText="Отмена"
        okButtonProps={{ danger: true }}
      >
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Text>
            Какой процент ликвидности удалить из позиции{' '}
            {(() => {
              const pool = removeModalPos ? poolById.get(removeModalPos.poolId) : undefined
              return pool ? `${pool.tokenXSymbol}/${pool.tokenYSymbol}` : ''
            })()}
            ?
          </Text>
          <Slider min={1} max={100} value={removePercent} onChange={setRemovePercent}
            marks={{ 25: '25%', 50: '50%', 75: '75%', 100: '100%' }} />
          <Text strong style={{ textAlign: 'center', display: 'block', fontSize: 24, color: '#EF4444' }}>
            {removePercent}%
          </Text>
        </Space>
      </Modal>
    </Space>
  )
}
