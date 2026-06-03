import { useMemo, useState } from 'react'
import { Card, Typography, Space, InputNumber, Button, Spin, Alert, Tag, Tooltip, message, Segmented } from 'antd'
import { ArrowDownOutlined, ArrowUpOutlined, ThunderboltFilled, SwapOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { pools, balances } from '@/api/services'
import type { Pool, TokenBalance, SwapQuote } from '@/api/types'
import PartialFillNotice, { isPartialFill } from './PartialFillNotice'
import { formatCompact, formatTokenAmount, baseAnchoredRate } from '@/lib/format'
import { apiErrorMessage } from '@/lib/apiError'
import { uuid } from '../lib/uuid'

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
  /**
   * OB-01 — a price level the user picked from the order book. The
   * swap is market (amount-based, no price input), so we surface this
   * as a reference chip rather than a hard limit — it tells the user
   * which level they tapped and the implied X they'd get for the
   * quote-side at that price. Clears when they edit the amount.
   */
  pickedPrice?: number | null
}

const SLIPPAGE = 0.5 // %

export default function PoolSwapPanel({ pool, embedded = false, pickedPrice }: PoolSwapPanelProps) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  // Base asset = the non-SRUB side of the pair (X unless X itself is SRUB); the
  // swap is framed as Buy/Sell of THAT asset. Default to SELLING the base so the
  // headline rate reads as the intuitive price (₽ per SETH) and matches the
  // expected "продажа" framing — the user flagged that the pool swap opened on a
  // SETH *purchase* with no visible buy/sell control. The explicit toggle below
  // lets the user switch to Buy.
  const baseIsX = pool.tokenXSymbol !== 'SRUB'
  const baseSym = baseIsX ? pool.tokenXSymbol : pool.tokenYSymbol
  const sellDir: 'XtoY' | 'YtoX' = baseIsX ? 'XtoY' : 'YtoX' // base → quote
  const buyDir: 'XtoY' | 'YtoX' = baseIsX ? 'YtoX' : 'XtoY'  // quote → base
  const [direction, setDirection] = useState<'XtoY' | 'YtoX'>(sellDir)
  const [amountIn, setAmountIn] = useState<number | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const side: 'buy' | 'sell' = direction === sellDir ? 'sell' : 'buy'
  const setSide = (s: 'buy' | 'sell') => {
    setDirection(s === 'sell' ? sellDir : buyDir)
    setAmountIn(null)
  }

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
      idempotencyKey: uuid(),
    }),
    onSuccess: () => {
      setSuccess(t('swap.panel.done', {
        from: formatTokenAmount(quote?.amountIn ?? amountIn, tokenInSym),
        to: formatTokenAmount(quote?.amountOut, tokenOutSym),
      }))
      setError(null)
      setAmountIn(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['poolDetail', pool.id] })
      queryClient.invalidateQueries({ queryKey: ['myTransactions'] })
      setTimeout(() => setSuccess(null), 5000)
    },
    onError: (err: unknown) => {
      setError(apiErrorMessage(err, t('swap.panel.doneFallback')))
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

  // Partial fill — the pool can't absorb the whole input, so the backend quote
  // consumes only quote.amountIn (≤ typed) and the rest doesn't fit. See
  // PartialFillNotice; the flag also drives the CTA label below.
  const fillable = quote?.amountIn ?? null
  const partialFill = isPartialFill(fillable, amountIn)

  const body = (
    <>
      {/* Explicit Buy/Sell of the base asset — the panel used to open on a
          neutral flip with no side label, so "покупка или продажа?" was unclear
          (and it silently defaulted to a purchase). Mirrors the Simple-mode
          toggle; defaults to Продать. */}
      <Segmented
        block
        value={side}
        onChange={(v) => setSide(v as 'buy' | 'sell')}
        options={[
          { value: 'buy', label: <span style={{ color: 'var(--viz-up)' }}><ArrowUpOutlined /> {t('swap.side.buyAsset', { sym: baseSym })}</span> },
          { value: 'sell', label: <span style={{ color: 'var(--viz-down)' }}><ArrowDownOutlined /> {t('swap.side.sellAsset', { sym: baseSym })}</span> },
        ]}
        style={{ marginBottom: 12 }}
      />

      {/* OB-01 — order-book pick reference. The swap is market, so this
          is a hint ("вы выбрали этот уровень в стакане"), not a limit. */}
      {pickedPrice != null && pickedPrice > 0 && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 8,
            padding: '6px 12px',
            marginBottom: 12,
            background: 'var(--surface-1)',
            border: '1px solid var(--border-light)',
            borderRadius: 'var(--radius-sm)',
            fontSize: 'var(--text-xs)',
          }}
        >
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
            {t('swap.panel.obLevel')}
          </Text>
          <Text strong style={{ fontVariantNumeric: 'tabular-nums' }}>
            {pickedPrice.toLocaleString('ru-RU', { maximumFractionDigits: 6 })} {pool.tokenYSymbol}/{pool.tokenXSymbol}
          </Text>
        </div>
      )}

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
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{t('swap.from')}</Text>
          {inBalance && (
            <Space size={4}>
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                {t('swap.available')}: {formatCompact(inBalance.available)}
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
          aria-label={t('swap.swapDirection')}
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
          {t('swap.to')}
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
          {(() => {
            // Headline rate = the base asset's price (quote per base, e.g. ₽ per
            // SETH), pinned to the stable base so it never inverts on Buy↔Sell.
            const r = baseAnchoredRate(quote.amountIn, quote.amountOut, tokenInSym, tokenOutSym, baseSym)
            return (
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', padding: '4px 0', fontSize: 'var(--text-xs)', borderBottom: '1px solid var(--border-light)' }}>
                <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{t('swap.quote.rate')}</Text>
                <div style={{ textAlign: 'right' }}>
                  <Text strong style={{ fontSize: 'var(--text-xs)', fontVariantNumeric: 'tabular-nums', display: 'block' }}>{r?.forward ?? '—'}</Text>
                  {r && <Text type="secondary" style={{ fontSize: 'var(--text-xs)', fontVariantNumeric: 'tabular-nums', display: 'block' }}>{r.reverse}</Text>}
                </div>
              </div>
            )
          })()}
          <Row
            label={t('swap.quote.priceImpact')}
            value={`${quote.priceImpact.toFixed(2)}%`}
            colour={priceImpactColour}
          />
          <Row label={t('swap.quote.fee')} value={formatTokenAmount(quote.fee, tokenInSym, { compact: true })} />
          <Row label={t('swap.quote.minSlip', { value: SLIPPAGE })} value={formatTokenAmount(minAmountOut, tokenOutSym, { compact: true })} last />
        </div>
      )}

      {/* Insufficient warning */}
      {insufficient && (
        <Alert
          message={t('swap.panel.insufficientAlert', { sym: tokenInSym, available: formatCompact(inBalance?.available ?? 0) })}
          type="warning"
          showIcon
          style={{ marginBottom: 12, borderRadius: 'var(--radius-sm)' }}
        />
      )}

      {/* Partial-fill / limited-liquidity warning (shared component). */}
      {!insufficient && (
        <PartialFillNotice fillable={fillable} requested={amountIn} symbol={tokenInSym} />
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
          ? t('swap.cta.enterAmount')
          : quoteLoading
          ? t('swap.cta.calcQuote')
          : !quote
          ? t('swap.cta.waitQuote')
          : insufficient
          ? t('swap.cta.insufficient', { sym: tokenInSym })
          : partialFill && fillable != null
          ? t('swap.cta.swapAmount', { amount: formatTokenAmount(fillable, tokenInSym, { compact: true }) })
          : t('swap.cta.swapAmount', { amount: formatTokenAmount(amountIn, tokenInSym, { compact: true }) })}
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
          <Text strong>{t('swap.panel.title')}</Text>
        </Space>
      }
      extra={
        <Tag color="default" style={{ borderRadius: 'var(--radius-pill)', fontSize: 'var(--text-xs)' }}>
          {t('swap.panel.toleranceTag', { value: SLIPPAGE })}
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
