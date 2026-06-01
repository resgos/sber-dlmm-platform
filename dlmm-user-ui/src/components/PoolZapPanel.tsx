import { useMemo, useRef, useState } from 'react'
import { Typography, Space, InputNumber, Button, Segmented, Alert, Tag, Spin, Steps } from 'antd'
import { ThunderboltOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { pools, balances } from '@/api/services'
import type { Pool, TokenBalance, SwapQuote } from '@/api/types'
import { celebrateSberkot } from '@/components/sberkot/events'
import { formatCompact, formatTokenAmount } from '@/lib/format'
import { uuid } from '../lib/uuid'

const { Text } = Typography

/**
 * Sprint 16 (Meteora parity) — "Zap in": deposit a SINGLE token and become a
 * balanced LP in one action. Frontend-orchestrated (no backend change): swap
 * half of the input into the other side of the pair, then add both sides as
 * liquidity around the active bin. Uses the existing swap + add-liquidity
 * endpoints, so there's no new backend risk — only the two-step nature, which we
 * surface honestly: if the swap succeeds but the add fails, the user keeps the
 * swapped tokens (nothing is lost) and we tell them.
 *
 * <p>A 50/50 value split is an MVP approximation of the active bin's exact
 * X:Y ratio; the add deposits what fits and the (small) remainder stays on the
 * balance. For pure one-sided exposure, the «Добавить» tab's single-sided mode
 * needs no swap at all.
 */
const SLIPPAGE = 0.5 // %
const RANGE = 10 // ± bins around active for the SPOT add

export default function PoolZapPanel({ pool }: { pool: Pool }) {
  const queryClient = useQueryClient()
  const [depSide, setDepSide] = useState<'X' | 'Y'>(pool.tokenYSymbol === 'SRUB' ? 'Y' : 'X')
  const [amount, setAmount] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [step, setStep] = useState<0 | 1 | 2>(0) // 0 idle, 1 swapping, 2 adding
  const [done, setDone] = useState(false)
  // Tracks whether the swap leg already committed this run, so a post-swap
  // failure doesn't let a re-click double-swap.
  const swapDoneRef = useRef(false)

  const isDepX = depSide === 'X'
  const dep = isDepX ? { id: pool.tokenXId, sym: pool.tokenXSymbol } : { id: pool.tokenYId, sym: pool.tokenYSymbol }
  const other = isDepX ? { id: pool.tokenYId, sym: pool.tokenYSymbol } : { id: pool.tokenXId, sym: pool.tokenXSymbol }
  const half = amount && amount > 0 ? amount / 2 : 0

  const { data: myBalances } = useQuery({ queryKey: ['myBalances'], queryFn: balances.getMyBalances })
  const depBalance = (myBalances ?? []).find((b: TokenBalance) => b.tokenId === dep.id)
  const insufficient = amount != null && depBalance != null && amount > depBalance.available

  // Quote the "swap half" leg so we can preview the balanced add.
  const { data: quote, isLoading: quoteLoading } = useQuery<SwapQuote | null>({
    queryKey: ['zapQuote', pool.id, dep.id, half],
    queryFn: async () => (half > 0 ? pools.getSwapQuote({ poolId: pool.id, tokenInId: dep.id, amountIn: half }) : null),
    enabled: half > 0,
    retry: false,
  })

  const zap = useMutation({
    mutationFn: async () => {
      if (!amount || amount <= 0 || !quote) throw new Error('no-quote')
      swapDoneRef.current = false
      // minAmountOut is in HUMAN units (toRaw rounds at the API boundary). Do NOT
      // Math.floor it — a sub-1-token output would floor to 0 and disable the
      // slippage guard entirely.
      const minOut = quote.amountOut * (1 - SLIPPAGE / 100)
      // Step 1 — swap half of the deposit token into the other side.
      setStep(1)
      await pools.executeSwap({
        poolId: pool.id, tokenInId: dep.id, amountIn: half, minAmountOut: minOut,
        idempotencyKey: uuid(),
      })
      swapDoneRef.current = true
      // Step 2 — add both sides as liquidity around the active bin.
      setStep(2)
      const amountX = isDepX ? half : quote.amountOut
      const amountY = isDepX ? quote.amountOut : half
      try {
        await pools.addLiquidity({
          poolId: pool.id, amountX, amountY,
          binRangeMin: pool.activeBinId - RANGE, binRangeMax: pool.activeBinId + RANGE,
          strategy: 'SPOT', idempotencyKey: uuid(),
        })
      } catch (addErr) {
        // The swap already went through — be explicit so the user isn't confused.
        const e = addErr as { response?: { data?: { message?: string } } }
        throw new Error(
          `Обмен выполнен, но добавление ликвидности не удалось (${e?.response?.data?.message || 'ошибка'}). ` +
          `Обменянные токены остались на балансе — добавьте ликвидность вручную во вкладке «Добавить».`,
        )
      }
    },
    onSuccess: () => {
      swapDoneRef.current = false
      celebrateSberkot(`Zap выполнен — вы стали поставщиком ликвидности 🎉`)
      setDone(true)
      setError(null)
      setAmount(null)
      setStep(0)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['poolDetail', pool.id] })
      setTimeout(() => setDone(false), 6000)
    },
    onError: (err: Error) => {
      setError(err.message === 'no-quote' ? 'Ждём котировку…' : err.message)
      // If the swap already executed (the add leg failed), clear the amount so a
      // re-click can't double-swap — the user must re-enter to retry.
      if (swapDoneRef.current) setAmount(null)
      swapDoneRef.current = false
      setStep(0)
    },
  })

  const estLine = useMemo(() => {
    if (!quote || half <= 0) return null
    const xAmt = isDepX ? half : quote.amountOut
    const yAmt = isDepX ? quote.amountOut : half
    return `${formatTokenAmount(xAmt, pool.tokenXSymbol, { compact: true })} + ${formatTokenAmount(yAmt, pool.tokenYSymbol, { compact: true })}`
  }, [quote, half, isDepX, pool.tokenXSymbol, pool.tokenYSymbol])

  const busy = zap.isPending

  return (
    <>
      <Text type="secondary" style={{ fontSize: 'var(--text-xs)', display: 'block', marginBottom: 8 }}>
        Внесите один токен — половина автоматически обменяется на пару, и обе части добавятся в пул.
      </Text>

      {done && (
        <Alert message="Zap выполнен — позиция открыта" type="success" showIcon closable
          onClose={() => setDone(false)} style={{ marginBottom: 12, borderRadius: 'var(--radius-sm)' }} />
      )}
      {error && (
        <Alert message={error} type="error" showIcon closable onClose={() => setError(null)}
          style={{ marginBottom: 12, borderRadius: 'var(--radius-sm)' }} />
      )}

      <Segmented
        block
        value={depSide}
        onChange={(v) => { setDepSide(v as 'X' | 'Y'); setAmount(null); setError(null) }}
        options={[
          { label: `Внести ${pool.tokenXSymbol}`, value: 'X' },
          { label: `Внести ${pool.tokenYSymbol}`, value: 'Y' },
        ]}
        style={{ marginBottom: 12 }}
      />

      <div style={{ background: 'var(--surface-1)', borderRadius: 'var(--radius-md)', padding: 14, marginBottom: 8 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>Сумма</Text>
          {depBalance && (
            <Space size={4}>
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>Доступно: {formatCompact(depBalance.available)}</Text>
              <Button type="link" size="small" style={{ padding: '0 4px', fontSize: 'var(--text-xs)', height: 18, fontWeight: 600 }}
                onClick={() => setAmount(depBalance.available)}>MAX</Button>
            </Space>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Tag color="green" style={{ borderRadius: 'var(--radius-pill)', padding: '4px 12px', margin: 0, fontWeight: 600 }}>{dep.sym}</Tag>
          <InputNumber
            style={{ flex: 1, fontSize: 'var(--text-md)', fontWeight: 600 }}
            variant="borderless" placeholder="0.0" value={amount} onChange={(v) => setAmount(v)}
            min={0} controls={false}
            formatter={(v) => (v ? Number(v).toLocaleString('ru-RU') : '')}
            parser={(v) => Number((v || '').toString().replace(/\s/g, '')) as 0}
          />
        </div>
      </div>

      {/* Preview of the resulting balanced add */}
      <div style={{ padding: '8px 14px', marginBottom: 8 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>Обмен</Text>
          <Text style={{ fontSize: 'var(--text-xs)' }}>
            {half > 0 ? `${formatTokenAmount(half, dep.sym, { compact: true })} → ${quoteLoading ? '…' : formatTokenAmount(quote?.amountOut, other.sym, { compact: true })}` : '—'}
          </Text>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 4 }}>
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>Добавится в пул ≈</Text>
          <Text strong style={{ fontSize: 'var(--text-xs)', fontVariantNumeric: 'tabular-nums' }}>
            {quoteLoading ? <Spin size="small" /> : (estLine ?? '—')}
          </Text>
        </div>
      </div>

      {insufficient && (
        <Alert message={`Недостаточно ${dep.sym} — доступно ${formatCompact(depBalance?.available ?? 0)}`}
          type="warning" showIcon style={{ marginBottom: 12, borderRadius: 'var(--radius-sm)' }} />
      )}

      {busy && (
        <Steps
          size="small" current={step - 1} style={{ marginBottom: 12 }}
          items={[{ title: 'Обмен' }, { title: 'Добавление' }]}
        />
      )}

      <Button
        type="primary" block size="large" className="sber-swap-cta" icon={<ThunderboltOutlined />}
        loading={busy}
        disabled={!amount || amount <= 0 || !quote || insufficient || busy}
        onClick={() => zap.mutate()}
      >
        {!amount ? 'Введите сумму'
          : quoteLoading ? 'Расчёт…'
          : !quote ? 'Ждём котировку…'
          : insufficient ? `Недостаточно ${dep.sym}`
          : `Zap ${formatTokenAmount(amount, dep.sym, { compact: true })}`}
      </Button>
    </>
  )
}
