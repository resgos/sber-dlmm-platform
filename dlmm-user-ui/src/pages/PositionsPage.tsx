import { useMemo, useState } from 'react'
import { Table, Tag, Typography, Space, Button, Card, Modal, Slider, message, Row, Col, Tooltip } from 'antd'
import { DollarOutlined, DeleteOutlined, PieChartOutlined, TrophyOutlined, WalletOutlined, ClearOutlined, RiseOutlined, FallOutlined, BellOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { pools, fees } from '@/api/services'
import type { Position, Pool, FeeHistoryEntry } from '@/api/types'
import { KpiRow, PageHeader, TokenPairChip } from '@/components/sber'
import { formatCompact, formatRub, formatTokenAmount } from '@/lib/format'
import PositionAlertsDrawer from '@/components/PositionAlertsDrawer'
import HealthScoreBadge from '@/components/HealthScoreBadge'
import { usePositionAlertWatcher } from '@/lib/usePositionAlertWatcher'
import { positionAlertsStore } from '@/store/positionAlertsStore'
import { useSyncExternalStore } from 'react'
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
  const [alertsDrawerOpen, setAlertsDrawerOpen] = useState(false)
  // Sprint 10 (new feature) — show the alert count next to the bell
  // so the user knows whether they've configured any rules.
  const alertCount = useSyncExternalStore(
    positionAlertsStore.subscribe,
    () => positionAlertsStore.list().length,
    () => 0,
  )

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

  const positionsWithClaimableFees = activePositions.filter(
    (p) => p.unclaimedFeeX > 0 || p.unclaimedFeeY > 0,
  )

  /**
   * Sprint 9-DS-r4 (P1-9) — "Закрыть всё" mass-action, copied from
   * the HedgePage #6.14 pattern. Sequentially removes 100% of every
   * active position. Serial (not Promise.all) for two reasons:
   *  - same-pool concurrent removes would clobber each other under
   *    the optimistic-lock retry loop (#4.7), so we avoid the noise;
   *  - it gives the user a recoverable failure mode — first error
   *    stops the loop, all previously-closed positions stay closed
   *    (idempotency keys), and the remaining ones can be retried
   *    manually one-by-one.
   */
  const confirmRemoveAll = () => {
    if (activePositions.length === 0) return
    Modal.confirm({
      title: `Закрыть все позиции (${activePositions.length})?`,
      width: 480,
      content: (
        <Space direction="vertical" size={8}>
          <Text>
            Будут последовательно сняты <b>{activePositions.length}</b> активных
            позиций. С каждой будут одновременно забраны накопленные комиссии.
          </Text>
          <Text type="warning" style={{ fontSize: 12 }}>
            Операция необратима. Каждое снятие выполняется как обычная транзакция
            и может изменить цену пула.
          </Text>
          <Text type="secondary" style={{ fontSize: 11 }}>
            Позиции закрываются последовательно — это даёт более предсказуемое
            влияние на цены пулов и помогает локализовать любую ошибку.
          </Text>
        </Space>
      ),
      okText: `Закрыть все ${activePositions.length}`,
      okButtonProps: { danger: true },
      cancelText: 'Отмена',
      onOk: async () => {
        let closed = 0
        for (const pos of activePositions) {
          try {
            await pools.removeLiquidity({
              positionId: pos.id,
              percentage: 100,
              idempotencyKey: crypto.randomUUID(),
            })
            closed++
          } catch (e: any) {
            message.error(
              `Снятие позиции ${pos.id.slice(0, 6)}… не удалось: ${
                e?.response?.data?.message || 'ошибка'
              }. Закрыто ${closed} из ${activePositions.length}.`,
            )
            queryClient.invalidateQueries({ queryKey: ['myPositions'] })
            queryClient.invalidateQueries({ queryKey: ['myBalances'] })
            return
          }
        }
        message.success(`Закрыто позиций: ${closed}`)
        queryClient.invalidateQueries({ queryKey: ['myPositions'] })
        queryClient.invalidateQueries({ queryKey: ['myBalances'] })
        queryClient.invalidateQueries({ queryKey: ['myFeeSummary'] })
        queryClient.invalidateQueries({ queryKey: ['myFeeHistory'] })
      },
    })
  }

  /**
   * Sprint 9-DS-r4 (P1-9) — companion to "Закрыть всё". Just claims
   * fees on every position that has unclaimed, without removing them.
   * Same serial-and-stop-on-first-failure shape as remove-all.
   */
  const confirmClaimAll = () => {
    if (positionsWithClaimableFees.length === 0) return
    Modal.confirm({
      title: `Забрать комиссии со всех позиций (${positionsWithClaimableFees.length})?`,
      width: 480,
      content: (
        <Space direction="vertical" size={8}>
          <Text>
            Будут забраны накопленные комиссии с{' '}
            <b>{positionsWithClaimableFees.length}</b> позиций. Позиции остаются
            открытыми и продолжают зарабатывать.
          </Text>
        </Space>
      ),
      okText: `Забрать с ${positionsWithClaimableFees.length} позиций`,
      cancelText: 'Отмена',
      onOk: async () => {
        let claimed = 0
        for (const pos of positionsWithClaimableFees) {
          try {
            await fees.claimFees({ positionId: pos.id })
            claimed++
          } catch (e: any) {
            message.error(
              `Claim позиции ${pos.id.slice(0, 6)}… не удалось: ${
                e?.response?.data?.message || 'ошибка'
              }. Забрано с ${claimed} из ${positionsWithClaimableFees.length}.`,
            )
            queryClient.invalidateQueries({ queryKey: ['myPositions'] })
            queryClient.invalidateQueries({ queryKey: ['myBalances'] })
            queryClient.invalidateQueries({ queryKey: ['myFeeSummary'] })
            return
          }
        }
        message.success(`Забрано с позиций: ${claimed}`)
        queryClient.invalidateQueries({ queryKey: ['myPositions'] })
        queryClient.invalidateQueries({ queryKey: ['myBalances'] })
        queryClient.invalidateQueries({ queryKey: ['myFeeSummary'] })
        queryClient.invalidateQueries({ queryKey: ['myFeeHistory'] })
      },
    })
  }

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

      <Card
        className="sber-card"
        title={<Text strong>Позиции</Text>}
        extra={
          <Space>
            {/* Sprint 10 (new feature) — position alerts. Always
                visible (even with no positions) so the user can
                discover the feature; drawer shows the right CTA
                state internally. */}
            <Button
              size="small"
              icon={<BellOutlined />}
              onClick={() => setAlertsDrawerOpen(true)}
              aria-label="Открыть оповещения по позициям"
            >
              Алерты{alertCount > 0 ? ` (${alertCount})` : ''}
            </Button>
            {activePositions.length > 0 && (
              <>
                {/* Sprint 9-DS-r4 (P1-9) — mass-action pair: claim
                    fees on everything that has them, then close all if
                    the user wants to flatten the book. Mirrors the
                    HedgePage "Закрыть всё" pattern. */}
                <Button
                  size="small"
                  type="primary"
                  ghost
                  icon={<DollarOutlined />}
                  disabled={positionsWithClaimableFees.length === 0}
                  onClick={confirmClaimAll}
                >
                  Забрать всё ({positionsWithClaimableFees.length})
                </Button>
                <Button
                  size="small"
                  danger
                  icon={<ClearOutlined />}
                  onClick={confirmRemoveAll}
                >
                  Закрыть всё ({activePositions.length})
                </Button>
              </>
            )}
          </Space>
        }
      >
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
            {
              // Sprint 10 (new feature) — Position Health Score column.
              // Single 0-100 number with a 3-factor tooltip breakdown
              // (range fit / fee earning / age). See lib/positionHealth.ts
              // for the weights + calibration notes.
              title: <Tooltip title="Эвристическая оценка состояния позиции: соответствие диапазону, доходность по комиссиям, возраст. Не является инвестиционной рекомендацией.">Здоровье</Tooltip>,
              key: 'health',
              width: 90,
              align: 'center' as const,
              render: (_: unknown, r: Position) => <HealthScoreBadge position={r} pool={poolById.get(r.poolId)} />,
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
              // Sprint 9-DS-r4 (P1-10) — P&L column. Compares current
              // position value (server-supplied via PositionResponse)
              // against the original deposit (also server-supplied;
              // backed by the new initial_deposit_x/y columns added
              // in Liquibase 011). Computed in pair-quote currency
              // (tokenY units) since both sides convert to it via
              // the pool's current price. Legacy positions opened
              // before the schema migration carry initialDeposit=0
              // and show "—". Excludes fees (those have their own
              // column); pure mark-to-market P&L only.
              title: <Tooltip title="Текущая стоимость позиции минус её первоначальная стоимость (в единицах второй монеты пары). Не включает комиссии — они в отдельной колонке.">P&L</Tooltip>,
              key: 'pnl',
              align: 'right' as const,
              render: (_: unknown, r: Position) => {
                const pool = poolById.get(r.poolId)
                const price = pool?.currentPrice ?? 0
                const ySym = pool?.tokenYSymbol ?? 'Y'
                const initialX = r.initialDepositX ?? 0
                const initialY = r.initialDepositY ?? 0
                const currentX = r.currentValueX ?? 0
                const currentY = r.currentValueY ?? 0

                if (initialX === 0 && initialY === 0) {
                  return (
                    <Tooltip title="Позиция открыта до Sprint 9-DS-r4 — нет исходной стоимости.">
                      <Text type="secondary">—</Text>
                    </Tooltip>
                  )
                }

                const initialQuote = initialY + initialX * price
                const currentQuote = currentY + currentX * price
                const pnlQuote = currentQuote - initialQuote
                const pnlPct = initialQuote > 0 ? (pnlQuote / initialQuote) * 100 : 0
                const positive = pnlQuote >= 0

                return (
                  <Space direction="vertical" size={0} style={{ alignItems: 'flex-end' }}>
                    <Text
                      strong
                      style={{
                        color: positive ? 'var(--sber-green)' : '#DC2626',
                        fontVariantNumeric: 'tabular-nums',
                        fontSize: 13,
                      }}
                    >
                      {positive ? <RiseOutlined /> : <FallOutlined />}{' '}
                      {positive ? '+' : ''}
                      {formatTokenAmount(Math.abs(pnlQuote), ySym, { compact: true, maxFractionDigits: 2 })}
                    </Text>
                    <Text
                      type="secondary"
                      style={{
                        fontSize: 11,
                        color: positive ? 'var(--sber-green)' : '#DC2626',
                        fontVariantNumeric: 'tabular-nums',
                      }}
                    >
                      {positive ? '+' : ''}{pnlPct.toFixed(2)}%
                    </Text>
                  </Space>
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

      {/* Sprint 10 (new feature) — alert watcher + manager drawer.
          Hook fires once per myPositions/poolPage refresh; cooldown is
          handled inside the store. */}
      <PositionAlertsWatcherSlot positions={myPositions} pools={poolPage?.content ?? undefined} />
      <PositionAlertsDrawer
        open={alertsDrawerOpen}
        onClose={() => setAlertsDrawerOpen(false)}
        positions={activePositions}
      />
    </Space>
  )
}

/**
 * Sprint 10 (new feature) — tiny render-less helper.
 *
 * The watcher hook must be called at the top of a component (Rules
 * of Hooks). Mounting it on a sub-component keeps PositionsPage's
 * own hook order stable and gives us a clean place to thread the
 * positions + pools props in.
 */
function PositionAlertsWatcherSlot({
  positions,
  pools,
}: {
  positions: Position[] | undefined
  pools: Pool[] | undefined
}) {
  usePositionAlertWatcher(positions, pools)
  return null
}
