import { useMemo, useState } from 'react'
import { Card, Typography, Space, InputNumber, Button, Spin, Alert, Tag, Tooltip, message } from 'antd'
import { ArrowDownOutlined, ThunderboltFilled, SwapOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { pools, balances } from '@/api/services'
import type { Pool, TokenBalance, SwapQuote } from '@/api/types'
import { formatCompact, formatTokenAmount } from '@/lib/format'

const { Text } = Typography

/**
 * Sprint 9-DS-r3 — Meteora-style pool-embedded swap panel.
 *
 * <p>Lifted from the requirement "не хватает функций обмена прям в
 * пуле" — Meteora's Dynamic Terminal lets you swap directly inside
 * the pool page without bouncing to /swap. This is a slim,
 * pool-scoped variant of SwapPage: no token picker (pair is fixed
 * to this pool's tokens), no popular-pairs strip, just amount + flip
 * + execute. Mechanics reuse {@link pools.getSwapQuote} and
 * {@link pools.executeSwap}.
 */
interface PoolSwapPanelProps {
  pool: Pool
  /**
   * Sprint 9-DS-r4 — when true, the panel renders without its outer
   * Card wrapper so it can sit inside another container (e.g. a
   * <Tabs> inside PoolActionTabs). The title/extra row is also
   * dropped because the parent tab label already says "Обменять".
   */
  embedded?: boolean
}

const SLIPPAGE = 0.5 // %

export default function PoolSwapPanel({ pool, embedded = false }: PoolSwapPanelProps) {
  const queryClient = useQueryClient()
  const [direction, setDirection] = useState<'XtoY' | 'YtoX'>(
    pool.tokenYSymbol === 'SRUB' ? 'YtoX' : 'XtoY',
  )
  const [amountIn, setAmountIn] = useState<number | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const tokenInId = direction === 'XtoY' ? pool.tokenXId : pool.tokenYId
  const tokenOutId = direction === 'XtoY' ? pool.tokenYId : pool.tokenXId
  const tokenInSym = direction === 'XtoY' ? pool.tokenXSymbol : pool.tokenYSymbol
  const tokenOutSym = direction === 'XtoY' ? pool.tokenYSymbol : pool.tokenXSymbol

  const { data: myBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })
  const inBalance = (myBalances ?? []).find((b: TokenBalance) => b.tokenId === tokenInId)

  const { data: quote, isLoading: quoteLoading } = useQuery<SwapQuote | null>({
    queryKey: ['poolSwapQuote', pool.id, tokenInId, amountIn],
    queryFn: async () => {
      if (!amountIn || amountIn <= 0) return null
      return pools.getSwapQuote({ poolId: pool.id, tokenInId, amountIn })
    },
    enabled: !!amountIn && amountIn > 0,
    retry: false,
  })

  const minAmountOut = quote ? Math.floor(quote.amountOut * (1 - SLIPPAGE / 100)) : 0

  const swapMutation = useMutation({
    mutationFn: () => pools.executeSwap({
      poolId: pool.id,
      tokenInId,
      amountIn: amountIn!,
      minAmountOut,
      idempotencyKey: crypto.randomUUID(),
    }),
    onSuccess: () => {
      setSuccess(`Обмен выполнен: ${formatTokenAmount(amountIn, tokenInSym)} → ${formatTokenAmount(quote?.amountOut, tokenOutSym)}`)
      setError(null)
      setAmountIn(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['poolDetail', pool.id] })
      queryClient.invalidateQueries({ queryKey: ['myTransactions'] })
      setTimeout(() => setSuccess(null), 5000)
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } }
      setError(e?.response?.data?.message || 'Не удалось выполнить обмен')
    },
  })

  const flipDirection = () => {
    setDirection((d) => (d === 'XtoY' ? 'YtoX' : 'XtoY'))
    setAmountIn(null)
  }

  const priceImpactColour =
    !quote?.priceImpact ? undefined
      : quote.priceImpact < 0.5 ? 'var(--sber-green)'
      : quote.priceImpact < 2 ? 'var(--color-warning-amber)' : 'var(--color-negative)'

  const insufficient = amountIn != null && inBalance != null && amountIn > inBalance.available

  const body = (
    <>
      {success && (
        <Alert
          message={success}
          type="success"
          showIcon
          closable
          onClose={() => setSuccess(null)}
          style={{ marginBottom: 12, borderRadius: 'var(--radius-sm)' }}
        />
      )}
      {error && (
        <Alert
          message={error}
          type="error"
          showIcon
          closable
          onClose={() => setError(null)}
          style={{ marginBottom: 12, borderRadius: 'var(--radius-sm)' }}
        />
      )}

      {/* In side */}
      <div
        style={{
          background: 'var(--surface-1, #F9FAFB)',
          borderRadius: 'var(--radius-md)',
          padding: 14,
          marginBottom: 8,
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>Вы отдаёте</Text>
          {inBalance && (
            <Space size={4}>
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                Доступно: {formatCompact(inBalance.available)}
              </Text>
              <Button
                type="link"
                size="small"
                style={{ padding: '0 4px', fontSize: 'var(--text-xs)', height: 18, fontWeight: 600 }}
                onClick={() => setAmountIn(inBalance.available)}
              >
                MAX
              </Button>
            </Space>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Tag color="green" style={{ borderRadius: 'var(--radius-pill)', padding: '4px 12px', margin: 0, fontWeight: 600 }}>
            {tokenInSym}
          </Tag>
          <InputNumber
            style={{ flex: 1, fontSize: 'var(--text-md)', fontWeight: 600 }}
            variant="borderless"
            placeholder="0.0"
            value={amountIn}
            onChange={(v) => setAmountIn(v)}
            min={0}
            controls={false}
            formatter={(v) => (v ? Number(v).toLocaleString('ru-RU') : '')}
            parser={(v) => Number((v || '').toString().replace(/\s/g, '')) as 0}
          />
        </div>
      </div>

      {/* Flip */}
      <div style={{ display: 'flex', justifyContent: 'center', margin: '-2px 0' }}>
        <Button
          shape="circle"
          icon={<ArrowDownOutlined />}
          onClick={flipDirection}
          size="small"
          aria-label="Поменять направление"
        />
      </div>

      {/* Out side */}
      <div
        style={{
          background: 'var(--surface-1, #F9FAFB)',
          borderRadius: 'var(--radius-md)',
          padding: 14,
          marginTop: 8,
          marginBottom: 12,
        }}
      >
        <Text type="secondary" style={{ fontSize: 'var(--text-xs)', display: 'block', marginBottom: 6 }}>
          Вы получаете
        </Text>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Tag color="blue" style={{ borderRadius: 'var(--radius-pill)', padding: '4px 12px', margin: 0, fontWeight: 600 }}>
            {tokenOutSym}
          </Tag>
          <div style={{ flex: 1, fontSize: 'var(--text-md)', fontWeight: 600, color: 'var(--text-primary)', fontVariantNumeric: 'tabular-nums', textAlign: 'right' }}>
            {quoteLoading ? <Spin size="small" /> : quote?.amountOut ? quote.amountOut.toLocaleString('ru-RU') : '0.0'}
          </div>
        </div>
      </div>

      {/* Quote summary */}
      {quote && !quoteLoading && (
        <div style={{ padding: '8px 12px', background: 'var(--surface-1, #F9FAFB)', borderRadius: 'var(--radius-sm)', marginBottom: 12 }}>
          <Row label="Курс" value={`1 ${tokenInSym} ≈ ${(quote.amountOut / quote.amountIn).toFixed(6)} ${tokenOutSym}`} />
          <Row
            label="Влияние на цену"
            value={`${quote.priceImpact.toFixed(2)}%`}
            colour={priceImpactColour}
          />
          <Row label="Комиссия" value={formatTokenAmount(quote.fee, tokenInSym, { compact: true })} />
          <Row label={`Мин. (slip ${SLIPPAGE}%)`} value={formatTokenAmount(minAmountOut, tokenOutSym, { compact: true })} last />
        </div>
      )}

      {/* Insufficient warning */}
      {insufficient && (
        <Alert
          message={`Недостаточно ${tokenInSym} — доступно ${formatCompact(inBalance?.available ?? 0)}`}
          type="warning"
          showIcon
          style={{ marginBottom: 12, borderRadius: 'var(--radius-sm)' }}
        />
      )}

      <Button
        type="primary"
        block
        size="large"
        className="sber-swap-cta"
        loading={swapMutation.isPending}
        disabled={!quote || !amountIn || insufficient || swapMutation.isPending}
        onClick={() => swapMutation.mutate()}
      >
        {!amountIn
          ? 'Введите сумму'
          : quoteLoading
          ? 'Расчёт котировки…'
          : !quote
          ? 'Ждём котировку…'
          : insufficient
          ? `Недостаточно ${tokenInSym}`
          : `Обменять ${formatTokenAmount(amountIn, tokenInSym, { compact: true })}`}
      </Button>
    </>
  )

  if (embedded) {
    // Sprint 9-DS-r4 — when nested inside another container (Pool
    // Action Tabs), drop the outer Card so we don't get the
    // double-border / double-padding look.
    return body
  }

  return (
    <Card
      className="sber-card"
      style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-light)' }}
      title={
        <Space size={8}>
          <SwapOutlined style={{ color: 'var(--sber-green)' }} />
          <Text strong>Быстрый обмен</Text>
        </Space>
      }
      extra={
        <Tag color="default" style={{ borderRadius: 'var(--radius-pill)', fontSize: 'var(--text-xs)' }}>
          допуск {SLIPPAGE}%
        </Tag>
      }
    >
      {body}
    </Card>
  )
}

function Row({ label, value, colour, last }: { label: string; value: string; colour?: string; last?: boolean }) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        padding: '4px 0',
        fontSize: 'var(--text-xs)',
        borderBottom: last ? 'none' : '1px solid var(--border-light)',
      }}
    >
      <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{label}</Text>
      <Text strong style={{ fontSize: 'var(--text-xs)', color: colour, fontVariantNumeric: 'tabular-nums' }}>{value}</Text>
    </div>
  )
}
