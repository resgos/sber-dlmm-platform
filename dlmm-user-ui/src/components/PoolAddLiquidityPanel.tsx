import { useState } from 'react'
import { Card, Typography, Space, InputNumber, Button, Alert, Tag } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { pools, balances } from '@/api/services'
import type { Pool, LiquidityStrategy } from '@/api/types'
import { formatCompact } from '@/lib/format'

const { Text } = Typography

/**
 * Sprint 9-DS-r4 — Meteora-style "Create Position" panel embedded in
 * the pool page (one of the right-rail tabs).
 *
 * <p>Lifts the add-liquidity form from /pools/:id/liquidity into the
 * pool page so an LP never leaves the pool to manage their position.
 * Mirror of Meteora's Dynamic Terminal: Amount → Strategy (Spot /
 * Curve / Bid Ask with SVG glyph icons) → Price Range → Add.
 */

interface PoolAddLiquidityPanelProps {
  pool: Pool
}

// Inline SVG glyphs matching Meteora's strategy icons.
const StrategyIcon = ({ kind }: { kind: LiquidityStrategy }) => {
  const bars = (heights: number[]) => (
    <svg width="40" height="18" viewBox="0 0 40 18" style={{ display: 'block' }}>
      {heights.map((h, i) => (
        <rect
          key={i}
          x={i * 5 + 1}
          y={18 - h}
          width="3"
          height={h}
          fill="currentColor"
          rx="0.5"
        />
      ))}
    </svg>
  )
  switch (kind) {
    case 'SPOT':
      // Flat / uniform: equal-height bars (real Meteora pattern).
      return bars([10, 10, 10, 10, 10, 10, 10])
    case 'CURVE':
      // Peak at middle (bell-ish): low on edges, tall in middle.
      return bars([4, 7, 11, 14, 11, 7, 4])
    case 'BID_ASK':
      // U-shape: tall on edges, low in middle.
      return bars([13, 9, 4, 2, 4, 9, 13])
  }
}

const STRATEGIES: { value: LiquidityStrategy; label: string }[] = [
  { value: 'SPOT', label: 'Spot' },
  { value: 'CURVE', label: 'Curve' },
  { value: 'BID_ASK', label: 'Bid Ask' },
]

export default function PoolAddLiquidityPanel({ pool }: PoolAddLiquidityPanelProps) {
  const queryClient = useQueryClient()
  const [strategy, setStrategy] = useState<LiquidityStrategy>('SPOT')
  // Default to ±10 bins around the active bin (matches our hint copy).
  const [binMin, setBinMin] = useState<number | null>(pool.activeBinId - 10)
  const [binMax, setBinMax] = useState<number | null>(pool.activeBinId + 10)
  const [amountX, setAmountX] = useState<number | null>(null)
  const [amountY, setAmountY] = useState<number | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const { data: myBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })
  const balanceX = myBalances?.find((b) => b.symbol === pool.tokenXSymbol)
  const balanceY = myBalances?.find((b) => b.symbol === pool.tokenYSymbol)

  const addMutation = useMutation({
    mutationFn: () =>
      pools.addLiquidity({
        poolId: pool.id,
        amountX: amountX!,
        amountY: amountY!,
        binRangeMin: binMin!,
        binRangeMax: binMax!,
        strategy,
        idempotencyKey: crypto.randomUUID(),
      }),
    onSuccess: () => {
      setSuccess('Ликвидность успешно добавлена')
      setError(null)
      setAmountX(null)
      setAmountY(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['poolDetail', pool.id] })
      setTimeout(() => setSuccess(null), 5000)
    },
    onError: (err: any) => {
      setError(err?.response?.data?.message || 'Ошибка при добавлении ликвидности')
    },
  })

  const totalBins =
    binMin != null && binMax != null && binMax >= binMin ? binMax - binMin + 1 : 0
  const canAdd =
    amountX != null && amountY != null && amountX > 0 && amountY > 0 &&
    binMin != null && binMax != null && binMax > binMin && totalBins <= 1000

  return (
    <Space direction="vertical" size={14} style={{ width: '100%' }}>
      {success && (
        <Alert
          message={success}
          type="success"
          showIcon
          closable
          onClose={() => setSuccess(null)}
          style={{ borderRadius: 10 }}
        />
      )}
      {error && (
        <Alert
          message={error}
          type="error"
          showIcon
          closable
          onClose={() => setError(null)}
          style={{ borderRadius: 10 }}
        />
      )}

      {/* Amount */}
      <div>
        <Text type="secondary" style={{ fontSize: 11, letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: 500 }}>
          Сумма
        </Text>
        <Space direction="vertical" size={8} style={{ width: '100%', marginTop: 8 }}>
          <AmountField
            symbol={pool.tokenXSymbol}
            value={amountX}
            onChange={setAmountX}
            available={balanceX?.available ?? 0}
          />
          <AmountField
            symbol={pool.tokenYSymbol}
            value={amountY}
            onChange={setAmountY}
            available={balanceY?.available ?? 0}
          />
        </Space>
      </div>

      {/* Strategy with Meteora-style icon buttons */}
      <div>
        <Text type="secondary" style={{ fontSize: 11, letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: 500 }}>
          Стратегия
        </Text>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(3, 1fr)',
            gap: 6,
            marginTop: 8,
          }}
        >
          {STRATEGIES.map((s) => {
            const active = strategy === s.value
            return (
              <button
                key={s.value}
                type="button"
                onClick={() => setStrategy(s.value)}
                style={{
                  border: `1px solid ${active ? 'var(--sber-green)' : 'var(--border-light)'}`,
                  background: active ? 'rgba(33,160,56,0.08)' : '#fff',
                  borderRadius: 10,
                  padding: '10px 8px',
                  cursor: 'pointer',
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  gap: 6,
                  color: active ? 'var(--sber-green)' : 'var(--text-secondary)',
                  transition: 'all 0.15s',
                }}
              >
                <StrategyIcon kind={s.value} />
                <span style={{ fontSize: 12, fontWeight: 600 }}>{s.label}</span>
              </button>
            )
          })}
        </div>
      </div>

      {/* Price range — bin numbers */}
      <div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <Text type="secondary" style={{ fontSize: 11, letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: 500 }}>
            Диапазон цен
          </Text>
          <Space size={6}>
            {[5, 10, 20].map((width) => (
              <Button
                key={width}
                size="small"
                type="text"
                style={{ fontSize: 11, padding: '0 6px', height: 22, color: 'var(--sber-green)', fontWeight: 600 }}
                onClick={() => {
                  setBinMin(pool.activeBinId - width)
                  setBinMax(pool.activeBinId + width)
                }}
              >
                ±{width}
              </Button>
            ))}
          </Space>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          <div>
            <Text type="secondary" style={{ fontSize: 11 }}>Мин бин</Text>
            <InputNumber
              value={binMin}
              onChange={(v) => setBinMin(v)}
              style={{ width: '100%' }}
              max={pool.activeBinId}
            />
          </div>
          <div>
            <Text type="secondary" style={{ fontSize: 11 }}>Макс бин</Text>
            <InputNumber
              value={binMax}
              onChange={(v) => setBinMax(v)}
              style={{ width: '100%' }}
              min={pool.activeBinId}
            />
          </div>
        </div>
        <div style={{ marginTop: 8, fontSize: 11, color: 'var(--text-muted)' }}>
          Активный бин: <span style={{ fontFamily: 'JetBrains Mono, monospace' }}>{pool.activeBinId}</span>
          {totalBins > 0 && (
            <>
              {' · '}
              <Tag color="default" style={{ borderRadius: 999, fontSize: 10, padding: '0 6px', lineHeight: '16px' }}>
                {totalBins} бин{totalBins === 1 ? '' : totalBins < 5 ? 'а' : 'ов'}
              </Tag>
            </>
          )}
        </div>
      </div>

      <Button
        type="primary"
        block
        size="large"
        className="sber-swap-cta"
        icon={<PlusOutlined />}
        disabled={!canAdd || addMutation.isPending}
        loading={addMutation.isPending}
        onClick={() => addMutation.mutate()}
      >
        {!amountX || !amountY
          ? 'Введите суммы'
          : !binMin || !binMax || binMax <= binMin
          ? 'Укажите диапазон'
          : 'Добавить ликвидность'}
      </Button>
    </Space>
  )
}

function AmountField({
  symbol,
  value,
  onChange,
  available,
}: {
  symbol: string
  value: number | null
  onChange: (v: number | null) => void
  available: number
}) {
  return (
    <div
      style={{
        background: 'var(--surface-1, #F9FAFB)',
        borderRadius: 12,
        padding: 12,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
        <Tag color="green" style={{ borderRadius: 999, padding: '2px 10px', margin: 0, fontWeight: 600 }}>
          {symbol}
        </Tag>
        <Space size={4}>
          <Text type="secondary" style={{ fontSize: 11 }}>
            Доступно: {formatCompact(available)}
          </Text>
          <Button
            type="link"
            size="small"
            style={{ padding: '0 4px', fontSize: 11, height: 18, fontWeight: 600 }}
            onClick={() => onChange(available)}
          >
            MAX
          </Button>
        </Space>
      </div>
      <InputNumber
        style={{ width: '100%', fontSize: 16, fontWeight: 600 }}
        variant="borderless"
        placeholder="0.0"
        value={value}
        onChange={(v) => onChange(v)}
        min={0}
        controls={false}
        formatter={(v) => (v ? Number(v).toLocaleString('ru-RU') : '')}
        parser={(v) => Number((v || '').toString().replace(/\s/g, '')) as 0}
      />
    </div>
  )
}
