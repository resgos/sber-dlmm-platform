import { useState, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Card, Typography, Space, Button, InputNumber, Tabs, Table, Tag, Slider, Modal,
  Alert, Spin, Divider, message,
} from 'antd'
import { ArrowLeftOutlined, PlusOutlined, DeleteOutlined, DollarOutlined, WarningOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { pools, balances, fees } from '@/api/services'
import type { Position, LiquidityStrategy } from '@/api/types'
import StrategySelector from '@/components/StrategySelector'
import { strategyLabel } from '@/lib/strategy'
import { presetBinRange, BIN_RANGE_PRESETS } from '@/lib/binRange'
import BinLiquidityChart from '@/components/BinLiquidityChart'
import RiskDisclosure from '@/components/RiskDisclosure'
import ModalHeader from '@/components/ModalHeader'
import AddLiquidityPreview from '@/components/AddLiquidityPreview'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import { formatCompact, formatTokenAmount } from '@/lib/format'
import { apiErrorMessage } from '@/lib/apiError'
import { uuid } from '../lib/uuid'

const { Title, Text } = Typography

export default function LiquidityPage() {
  // 2026-07-06 — page wired to i18n (was hardcoded Russian; an EN investor
  // hit a Russian add-liquidity flow). Copy lives under liquidityPage.*.
  const { t } = useTranslation()
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [strategy, setStrategy] = useState<LiquidityStrategy>('SPOT')
  const [binMin, setBinMin] = useState<number | null>(null)
  const [binMax, setBinMax] = useState<number | null>(null)
  const [amountX, setAmountX] = useState<number | null>(null)
  const [amountY, setAmountY] = useState<number | null>(null)
  const [removeModalPos, setRemoveModalPos] = useState<Position | null>(null)
  const [removePercent, setRemovePercent] = useState(100)

  const { data: pool, isLoading } = useQuery({
    queryKey: ['poolDetail', id],
    queryFn: () => pools.getPool(id!),
    enabled: !!id,
  })

  const { data: myBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })

  const { data: myPositions } = useQuery({
    queryKey: ['myPositions'],
    queryFn: pools.getMyPositions,
  })

  const poolPositions = useMemo(
    () => (myPositions || []).filter((p: Position) => p.poolId === id && p.isActive),
    [myPositions, id],
  )

  const balanceX = myBalances?.find((b) => b.symbol === pool?.tokenXSymbol)
  const balanceY = myBalances?.find((b) => b.symbol === pool?.tokenYSymbol)

  // Same debounced inputs + query key as the AddLiquidityPreview
  // component so both share one React Query cache entry (single
  // network round-trip per pause). Submit button reads from this to
  // surface a warning chip when the preview returns warnings.
  const dAmountX = useDebouncedValue(amountX, 300)
  const dAmountY = useDebouncedValue(amountY, 300)
  const dBinMin = useDebouncedValue(binMin, 300)
  const dBinMax = useDebouncedValue(binMax, 300)
  const previewEnabled = !!(
    id && dAmountX && dAmountY && dBinMin != null && dBinMax != null && dBinMin <= dBinMax
  )
  const { data: previewData } = useQuery({
    queryKey: ['preview-add-liquidity', id, dAmountX, dAmountY, dBinMin, dBinMax, strategy],
    queryFn: () => pools.previewAddLiquidity({
      poolId: id!,
      amountX: dAmountX!,
      amountY: dAmountY!,
      binRangeMin: dBinMin!,
      binRangeMax: dBinMax!,
      strategy,
    }),
    enabled: previewEnabled,
    staleTime: 5000,
    retry: false,
  })

  const addMutation = useMutation({
    mutationFn: () =>
      pools.addLiquidity({
        poolId: id!,
        amountX: amountX!,
        amountY: amountY!,
        binRangeMin: binMin!,
        binRangeMax: binMax!,
        strategy,
        idempotencyKey: uuid(),
      }),
    onSuccess: () => {
      message.success(t('liquidityPage.messages.added'))
      setAmountX(null)
      setAmountY(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['poolDetail', id] })
    },
    onError: (err: any) => {
      message.error(apiErrorMessage(err, t('liquidityPage.messages.addError')))
    },
  })

  const removeMutation = useMutation({
    mutationFn: (positionId: string) =>
      pools.removeLiquidity({
        positionId,
        percentage: removePercent,
        idempotencyKey: uuid(),
      }),
    onSuccess: () => {
      message.success(t('liquidityPage.messages.removed'))
      setRemoveModalPos(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['poolDetail', id] })
    },
    onError: (err: any) => {
      message.error(apiErrorMessage(err, t('liquidityPage.messages.removeError')))
    },
  })

  const claimMutation = useMutation({
    mutationFn: (positionId: string) => fees.claimFees({ positionId }),
    onSuccess: () => {
      message.success(t('liquidityPage.messages.feesClaimed'))
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
    },
    onError: (err: any) => {
      message.error(apiErrorMessage(err, t('liquidityPage.messages.error')))
    },
  })

  if (isLoading) return <div style={{ textAlign: 'center', padding: '80px 0' }}><Spin size="large" /></div>
  if (!pool) return <Alert message={t('liquidityPage.poolNotFound')} type="error" showIcon />

  const canAdd = amountX && amountY && binMin != null && binMax != null && binMin < binMax

  const tabItems = [
    {
      key: 'add',
      label: t('liquidityPage.tabs.add'),
      children: (
        <Space direction="vertical" size={20} style={{ width: '100%' }}>
          <div>
            <Text strong style={{ display: 'block', marginBottom: 12 }}>{t('liquidityPage.strategyTitle')}</Text>
            <StrategySelector value={strategy} onChange={setStrategy} />
          </div>

          <div>
            <Text strong style={{ display: 'block', marginBottom: 8 }}>{t('liquidityPage.binRangeTitle')}</Text>
            {/* One-click range presets around the active bin — investors pick a
                concentration band (±N bins) instead of typing raw bin indices.
                Neutral "±N" labels keep this gate-safe on an otherwise RU page. */}
            <Space size={8} style={{ marginBottom: 8 }}>
              {BIN_RANGE_PRESETS.map((p) => {
                const r = presetBinRange(pool.activeBinId, p.halfWidth)
                const active = binMin === r.min && binMax === r.max
                return (
                  <Button
                    key={p.halfWidth}
                    size="small"
                    type={active ? 'primary' : 'default'}
                    onClick={() => { setBinMin(r.min); setBinMax(r.max) }}
                  >
                    ±{p.halfWidth}
                  </Button>
                )
              })}
            </Space>
            <Space>
              <InputNumber
                placeholder={t('liquidityPage.binMinPlaceholder')}
                value={binMin}
                onChange={(v) => setBinMin(v)}
                style={{ width: 140 }}
              />
              <Text type="secondary">—</Text>
              <InputNumber
                placeholder={t('liquidityPage.binMaxPlaceholder')}
                value={binMax}
                onChange={(v) => setBinMax(v)}
                style={{ width: 140 }}
              />
            </Space>
            <div style={{ fontSize: 'var(--text-xs)', color: '#9CA3AF', marginTop: 4 }}>
              {t('liquidityPage.binHint', { bin: pool.activeBinId })}
            </div>
          </div>

          <div>
            <Text strong style={{ display: 'block', marginBottom: 8 }}>{t('liquidityPage.amountsTitle')}</Text>
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <Text>{pool.tokenXSymbol}</Text>
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                    {t('liquidityPage.available', { value: formatCompact(balanceX?.available ?? 0) })}
                    {balanceX && (
                      <Button type="link" size="small" style={{ padding: '0 4px', fontSize: 'var(--text-xs)' }}
                        onClick={() => setAmountX(balanceX.available)}>MAX</Button>
                    )}
                  </Text>
                </div>
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder="0.00"
                  value={amountX}
                  onChange={(v) => setAmountX(v)}
                  min={0}
                  size="large"
                  controls={false}
                />
              </div>
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <Text>{pool.tokenYSymbol}</Text>
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                    {t('liquidityPage.available', { value: formatCompact(balanceY?.available ?? 0) })}
                    {balanceY && (
                      <Button type="link" size="small" style={{ padding: '0 4px', fontSize: 'var(--text-xs)' }}
                        onClick={() => setAmountY(balanceY.available)}>MAX</Button>
                    )}
                  </Text>
                </div>
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder="0.00"
                  value={amountY}
                  onChange={(v) => setAmountY(v)}
                  min={0}
                  size="large"
                  controls={false}
                />
              </div>
            </Space>
          </div>

          {/* Sprint 11 G-22 — server-computed preview. Shows TVL share,
              in-range chip, fee/day projection, and warnings before user
              clicks Submit. Hidden until both amounts + bin range are
              filled in (matches the backend's @Min(1) constraint). */}
          <AddLiquidityPreview
            poolId={id!}
            amountX={amountX}
            amountY={amountY}
            binMin={binMin}
            binMax={binMax}
            strategy={strategy}
            tokenYSymbol={pool.tokenYSymbol}
          />

          <Button
            type="primary"
            block
            size="large"
            icon={<PlusOutlined />}
            disabled={!canAdd}
            loading={addMutation.isPending}
            onClick={() => addMutation.mutate()}
            style={{ height: 48, fontSize: 15, fontWeight: 600, borderRadius: 'var(--radius-sm)' }}
          >
            {t('liquidityPage.addButton')}
          </Button>
          {/* G-22 — surface warning chip near Submit so user sees the
              flag even if they scrolled past the preview card. Submit
              stays enabled — preview is informational, not blocking. */}
          {previewData && previewData.warnings.length > 0 && (
            <div style={{ textAlign: 'center', marginTop: -8 }}>
              <Tag icon={<WarningOutlined />} color="orange">
                {t('liquidityPage.warningsCheck', { count: previewData.warnings.length })}
              </Tag>
            </div>
          )}
        </Space>
      ),
    },
    {
      key: 'positions',
      label: t('liquidityPage.tabs.positions', { count: poolPositions.length }),
      children: poolPositions.length === 0 ? (
        <Text type="secondary">{t('liquidityPage.noPositions')}</Text>
      ) : (
        <Table
          className="sber-table"
          dataSource={poolPositions}
          rowKey="id"
          pagination={false}
          size="middle"
          columns={[
            { title: t('liquidityPage.table.strategy'), dataIndex: 'strategy', render: (s: string) => <Tag color="green">{strategyLabel(s)}</Tag> },
            { title: t('liquidityPage.table.range'), key: 'range', render: (_: unknown, r: Position) => `${r.binRangeMin} — ${r.binRangeMax}` },
            {
              title: t('liquidityPage.table.unclaimed', { symbol: pool.tokenXSymbol }),
              dataIndex: 'unclaimedFeeX', align: 'right' as const,
              render: (v: number) => (
                <span style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {formatTokenAmount(v, pool.tokenXSymbol, { compact: true })}
                </span>
              ),
            },
            {
              title: t('liquidityPage.table.unclaimed', { symbol: pool.tokenYSymbol }),
              dataIndex: 'unclaimedFeeY', align: 'right' as const,
              render: (v: number) => (
                <span style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {formatTokenAmount(v, pool.tokenYSymbol, { compact: true })}
                </span>
              ),
            },
            {
              title: t('liquidityPage.table.actions'),
              key: 'actions',
              render: (_: unknown, r: Position) => (
                <Space>
                  <Button size="small" icon={<DollarOutlined />} onClick={() => claimMutation.mutate(r.id)}
                    loading={claimMutation.isPending}
                    disabled={r.unclaimedFeeX === 0 && r.unclaimedFeeY === 0}>
                    {t('liquidityPage.table.claim')}
                  </Button>
                  <Button size="small" danger icon={<DeleteOutlined />} onClick={() => setRemoveModalPos(r)}>
                    {t('liquidityPage.table.remove')}
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      ),
    },
  ]

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <Space>
        <Button icon={<ArrowLeftOutlined />} type="text" onClick={() => navigate(`/pools/${id}`)} />
        <Title level={4} className="sber-page-title" style={{ margin: 0 }}>
          {t('liquidityPage.title', { pair: `${pool.tokenXSymbol}/${pool.tokenYSymbol}` })}
        </Title>
      </Space>

      <Card className="sber-card" title={<Text strong>{t('liquidityPage.chartTitle')}</Text>}>
        <BinLiquidityChart poolId={pool.id} />
      </Card>

      {/* Sprint 12 G-14 — risk disclosure banner. Always-on per
          compliance — never dismissible. */}
      <RiskDisclosure variant="lp" />

      <Card className="sber-card">
        <Tabs items={tabItems} />
      </Card>

      <Modal
        title={<ModalHeader title={t('liquidityPage.removeModal.title')} severity="danger" />}
        open={!!removeModalPos}
        onCancel={() => setRemoveModalPos(null)}
        onOk={() => removeModalPos && removeMutation.mutate(removeModalPos.id)}
        confirmLoading={removeMutation.isPending}
        okText={t('liquidityPage.removeModal.okText')}
        cancelText={t('common.cancel')}
        okButtonProps={{ danger: true }}
      >
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Text>{t('liquidityPage.removeModal.percentQuestion')}</Text>
          <Slider
            min={1}
            max={100}
            value={removePercent}
            onChange={setRemovePercent}
            marks={{ 25: '25%', 50: '50%', 75: '75%', 100: '100%' }}
          />
          <Text strong style={{ textAlign: 'center', display: 'block', fontSize: 24, color: 'var(--color-negative-strong)' }}>
            {removePercent}%
          </Text>
        </Space>
      </Modal>
    </Space>
  )
}
