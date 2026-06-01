import { useMemo, useState } from 'react'
import {
  Typography, Space, InputNumber, Button, Segmented, Alert, Tag, Popconfirm, Empty, Spin, message,
} from 'antd'
import { CloseOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { limitOrders, balances } from '@/api/services'
import type { Pool, TokenBalance, LimitOrderSide } from '@/api/types'
import { celebrateSberkot } from '@/components/sberkot/events'
import { formatCompact, formatTokenAmount } from '@/lib/format'
import { uuid } from '../lib/uuid'

const { Text } = Typography

/**
 * Sprint 16 (Meteora parity) — pool-embedded limit-order panel. The third tab
 * Meteora's Dynamic Terminal has (Create Position / Limit Order / Swap) that we
 * were missing. Place a BUY/SELL order that fills automatically at the chosen
 * price when the pool's market-synced price crosses it; the open orders for this
 * pool are listed below with one-click cancel (refunds the escrow).
 *
 * <p>Price is token_y per 1 token_x (the same number the pool header shows).
 * SELL escrows X and fills when price ≥ limit; BUY escrows Y and fills when
 * price ≤ limit — mirrored from the backend so the est-output preview matches.
 */
export default function LimitOrdersPanel({ pool }: { pool: Pool }) {
  const queryClient = useQueryClient()
  const [side, setSide] = useState<LimitOrderSide>('BUY')
  const [amountIn, setAmountIn] = useState<number | null>(null)
  const [price, setPrice] = useState<number | null>(pool.currentPrice || null)
  const [error, setError] = useState<string | null>(null)

  const isBuy = side === 'BUY'
  // BUY: spend Y (quote) to get X (base). SELL: sell X (base) to get Y (quote).
  const pay = isBuy
    ? { id: pool.tokenYId, sym: pool.tokenYSymbol }
    : { id: pool.tokenXId, sym: pool.tokenXSymbol }
  const get = isBuy
    ? { id: pool.tokenXId, sym: pool.tokenXSymbol }
    : { id: pool.tokenYId, sym: pool.tokenYSymbol }

  const estOut = useMemo(() => {
    if (!amountIn || amountIn <= 0 || !price || price <= 0) return 0
    return isBuy ? amountIn / price : amountIn * price
  }, [amountIn, price, isBuy])

  const { data: myBalances } = useQuery({ queryKey: ['myBalances'], queryFn: balances.getMyBalances })
  const payBalance = (myBalances ?? []).find((b: TokenBalance) => b.tokenId === pay.id)
  const insufficient = amountIn != null && payBalance != null && amountIn > payBalance.available

  const { data: openOrders, isLoading: ordersLoading } = useQuery({
    queryKey: ['myLimitOrders', pool.id],
    queryFn: () => limitOrders.getMine({ poolId: pool.id, status: 'OPEN' }),
  })

  // A BUY at/above market (or SELL at/below) would trigger on the next watcher tick.
  const fillsImmediately =
    price != null && pool.currentPrice > 0 &&
    (isBuy ? price >= pool.currentPrice : price <= pool.currentPrice)

  const placeMutation = useMutation({
    mutationFn: () => limitOrders.create({
      poolId: pool.id,
      side,
      amountIn: amountIn!,
      limitPrice: price!,
      idempotencyKey: uuid(),
    }),
    onSuccess: () => {
      celebrateSberkot(isBuy
        ? `Ордер на покупку ${get.sym} размещён 🎯`
        : `Ордер на продажу ${pay.sym} размещён 🎯`)
      setAmountIn(null)
      setError(null)
      queryClient.invalidateQueries({ queryKey: ['myLimitOrders'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } }
      setError(e?.response?.data?.message || 'Не удалось разместить ордер')
    },
  })

  const cancelMutation = useMutation({
    mutationFn: (id: string) => limitOrders.cancel(id),
    onSuccess: () => {
      message.success('Ордер отменён, средства возвращены')
      queryClient.invalidateQueries({ queryKey: ['myLimitOrders'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
    },
    onError: () => message.error('Не удалось отменить ордер'),
  })

  const canPlace = !!amountIn && amountIn > 0 && !!price && price > 0 && estOut > 0 && !insufficient

  return (
    <>
      {error && (
        <Alert
          message={error} type="error" showIcon closable onClose={() => setError(null)}
          style={{ marginBottom: 12, borderRadius: 'var(--radius-sm)' }}
        />
      )}

      <Segmented
        block
        value={side}
        onChange={(v) => { setSide(v as LimitOrderSide); setAmountIn(null); setError(null) }}
        options={[
          { label: `Купить ${pool.tokenXSymbol}`, value: 'BUY' },
          { label: `Продать ${pool.tokenXSymbol}`, value: 'SELL' },
        ]}
        style={{ marginBottom: 12 }}
      />

      {/* Pay amount */}
      <div style={{ background: 'var(--surface-1)', borderRadius: 'var(--radius-md)', padding: 14, marginBottom: 8 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
            {isBuy ? 'Потратить' : 'Продать'}
          </Text>
          {payBalance && (
            <Space size={4}>
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                Доступно: {formatCompact(payBalance.available)}
              </Text>
              <Button
                type="link" size="small"
                style={{ padding: '0 4px', fontSize: 'var(--text-xs)', height: 18, fontWeight: 600 }}
                onClick={() => setAmountIn(payBalance.available)}
              >
                MAX
              </Button>
            </Space>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Tag color="green" style={{ borderRadius: 'var(--radius-pill)', padding: '4px 12px', margin: 0, fontWeight: 600 }}>
            {pay.sym}
          </Tag>
          <InputNumber
            style={{ flex: 1, fontSize: 'var(--text-md)', fontWeight: 600 }}
            variant="borderless" placeholder="0.0" value={amountIn}
            onChange={(v) => setAmountIn(v)} min={0} controls={false}
            formatter={(v) => (v ? Number(v).toLocaleString('ru-RU') : '')}
            parser={(v) => Number((v || '').toString().replace(/\s/g, '')) as 0}
          />
        </div>
      </div>

      {/* Limit price */}
      <div style={{ background: 'var(--surface-1)', borderRadius: 'var(--radius-md)', padding: 14, marginBottom: 8 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>Цена исполнения</Text>
          {pool.currentPrice > 0 && (
            <Button
              type="link" size="small"
              style={{ padding: '0 4px', fontSize: 'var(--text-xs)', height: 18, fontWeight: 600 }}
              onClick={() => setPrice(pool.currentPrice)}
            >
              текущая: {pool.currentPrice.toLocaleString('ru-RU', { maximumFractionDigits: 6 })}
            </Button>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <InputNumber
            style={{ flex: 1, fontSize: 'var(--text-md)', fontWeight: 600 }}
            variant="borderless" placeholder="0.0" value={price}
            onChange={(v) => setPrice(v)} min={0} controls={false}
            formatter={(v) => (v ? Number(v).toLocaleString('ru-RU') : '')}
            parser={(v) => Number((v || '').toString().replace(/\s/g, '')) as 0}
          />
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)', whiteSpace: 'nowrap' }}>
            {pool.tokenYSymbol}/{pool.tokenXSymbol}
          </Text>
        </div>
      </div>

      {/* Estimated receive */}
      <div style={{ display: 'flex', justifyContent: 'space-between', padding: '4px 14px', marginBottom: 8 }}>
        <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>Получите ≈</Text>
        <Text strong style={{ fontSize: 'var(--text-sm)', fontVariantNumeric: 'tabular-nums' }}>
          {estOut > 0 ? formatTokenAmount(estOut, get.sym) : `0 ${get.sym}`}
        </Text>
      </div>

      {fillsImmediately && (
        <Alert
          message="Цена уже достигнута — ордер исполнится в ближайшую минуту"
          type="info" showIcon
          style={{ marginBottom: 12, borderRadius: 'var(--radius-sm)' }}
        />
      )}
      {insufficient && (
        <Alert
          message={`Недостаточно ${pay.sym} — доступно ${formatCompact(payBalance?.available ?? 0)}`}
          type="warning" showIcon
          style={{ marginBottom: 12, borderRadius: 'var(--radius-sm)' }}
        />
      )}

      <Button
        type="primary" block size="large" className="sber-swap-cta"
        loading={placeMutation.isPending}
        disabled={!canPlace || placeMutation.isPending}
        onClick={() => placeMutation.mutate()}
      >
        {!amountIn ? 'Введите сумму'
          : !price ? 'Введите цену'
          : insufficient ? `Недостаточно ${pay.sym}`
          : isBuy ? `Купить ${get.sym} по лимиту` : `Продать ${pay.sym} по лимиту`}
      </Button>

      {/* Open orders for this pool */}
      <div style={{ marginTop: 16 }}>
        <Text strong style={{ fontSize: 'var(--text-sm)', display: 'block', marginBottom: 8 }}>
          Мои ордера в этом пуле
        </Text>
        {ordersLoading ? (
          <div style={{ textAlign: 'center', padding: 16 }}><Spin size="small" /></div>
        ) : !openOrders || openOrders.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={<Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>Нет открытых ордеров</Text>}
            style={{ margin: '8px 0' }}
          />
        ) : (
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            {openOrders.map((o) => (
              <div
                key={o.id}
                style={{
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8,
                  padding: '8px 12px', background: 'var(--surface-1)',
                  border: '1px solid var(--border-light)', borderRadius: 'var(--radius-sm)',
                }}
              >
                <Space size={8} wrap>
                  <Tag color={o.side === 'BUY' ? 'green' : 'volcano'} style={{ margin: 0, fontWeight: 600 }}>
                    {o.side === 'BUY' ? 'Покупка' : 'Продажа'}
                  </Tag>
                  <Text style={{ fontSize: 'var(--text-xs)', fontVariantNumeric: 'tabular-nums' }}>
                    {formatTokenAmount(o.amountIn, o.tokenInSymbol ?? '', { compact: true })}
                    {' → '}
                    {formatTokenAmount(o.amountOut, o.tokenOutSymbol ?? '', { compact: true })}
                  </Text>
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                    @ {o.limitPrice.toLocaleString('ru-RU', { maximumFractionDigits: 6 })}
                  </Text>
                </Space>
                <Popconfirm
                  title="Отменить ордер?"
                  description="Зарезервированные средства вернутся на баланс."
                  okText="Отменить" cancelText="Нет"
                  onConfirm={() => cancelMutation.mutate(o.id)}
                >
                  <Button
                    type="text" size="small" danger icon={<CloseOutlined />}
                    aria-label="Отменить ордер"
                    loading={cancelMutation.isPending && cancelMutation.variables === o.id}
                  />
                </Popconfirm>
              </div>
            ))}
          </Space>
        )}
      </div>
    </>
  )
}
