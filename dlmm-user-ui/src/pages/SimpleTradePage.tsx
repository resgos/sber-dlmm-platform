import { useMemo, useState } from 'react'
import {
  Card, Select, InputNumber, Button, Typography, Space, Alert, Segmented, Spin, Divider, Tag,
} from 'antd'
import {
  ArrowUpOutlined, ArrowDownOutlined, PlusOutlined, ThunderboltFilled, InfoCircleOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { tokens, pools, balances } from '@/api/services'
import type { Token, Pool, TokenBalance } from '@/api/types'
import TokenChip from '@/components/TokenChip'
import { TokenPairChip } from '@/components/sber'
import { formatCompact, formatTokenAmount } from '@/lib/format'

const { Title, Text } = Typography

/**
 * SM-01 — Simple trading surface.
 *
 * A deliberately bare alternative to the Pro Swap/Liquidity pages for users
 * who just want to buy/sell or park liquidity without thinking about bins,
 * strategies, or ranges. Two cards:
 *
 *   1. Купить / Продать  — MARKET swap against the token's SRUB pool.
 *      Reuses pools.getSwapQuote + pools.executeSwap under the hood; the
 *      Buy/Sell direction just decides which side of the SRUB pair is
 *      tokenIn. One confirm, no slippage/bins UI (a sane default slippage
 *      is applied internally).
 *
 *   2. Добавить ликвидность (базовые настройки) — calls pools.addLiquidity
 *      with hard-coded sensible defaults: SPOT strategy, bin range
 *      [activeBinId-10, activeBinId+10]. No bin/strategy UI.
 *
 * Design per docs/DESIGN-DIRECTION-2026-05-29 "Simple ⇄ Pro" spec: airy
 * layout (--space-5/6), the primary buy/sell CTA card at Tier-2 elevation,
 * Buy uses --viz-up / Sell uses --viz-down. 0 hardcoded colors — all via
 * CSS vars / the .sber-simple-* classes in sber-theme.css.
 */

// The pricing/quote anchor for the whole catalogue. Every tradable token
// in the seed pairs against SRUB, so "buy SBER" = swap SRUB→SBER in the
// SBER/SRUB pool, "sell SBER" = swap SBER→SRUB. Matches DashboardPage's
// BASE_SYMBOL convention.
const BASE_SYMBOL = 'SRUB'

// Internal default slippage for the one-click market trade (Simple mode
// hides the slippage control). 0.5% mirrors the Pro Swap page default.
const SIMPLE_SLIPPAGE_PCT = 0.5

// SM-01 — basic add-liquidity defaults. ±10 bins around the active price,
// uniform (SPOT) distribution. Surfaced to the user as a one-line note.
const SIMPLE_BIN_HALF_RANGE = 10

type Side = 'buy' | 'sell'

export default function SimpleTradePage() {
  const queryClient = useQueryClient()

  // ── Buy/Sell card state ────────────────────────────────────────────
  const [side, setSide] = useState<Side>('buy')
  const [assetId, setAssetId] = useState<string>('')
  const [amount, setAmount] = useState<number | null>(null)
  const [tradeError, setTradeError] = useState<string | null>(null)
  const [tradeSuccess, setTradeSuccess] = useState<string | null>(null)

  // ── Add-liquidity card state ───────────────────────────────────────
  const [lpPoolId, setLpPoolId] = useState<string>('')
  const [lpAmountX, setLpAmountX] = useState<number | null>(null)
  const [lpAmountY, setLpAmountY] = useState<number | null>(null)
  const [lpError, setLpError] = useState<string | null>(null)
  const [lpSuccess, setLpSuccess] = useState<string | null>(null)

  const { data: tokenList } = useQuery({
    queryKey: ['tokens'],
    queryFn: () => tokens.getTokens(0, 100),
  })
  const { data: myBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })
  const { data: poolList } = useQuery({
    queryKey: ['pools'],
    queryFn: () => pools.getPools(0, 100),
  })

  const activePools = useMemo(
    () => (poolList?.content ?? []).filter((p: Pool) => p.status === 'ACTIVE'),
    [poolList],
  )

  const balanceMap = useMemo(
    () => new Map((myBalances ?? []).map((b: TokenBalance) => [b.symbol, b])),
    [myBalances],
  )

  // ── Buy/Sell: resolve the SRUB pool for the picked asset ────────────
  // We only let the user pick the *non-SRUB* asset; the counter-token is
  // always SRUB. assetPool is the SBER/SRUB pool (X=asset, Y=SRUB by seed
  // convention, but we resolve robustly).
  const assetTokens = useMemo(() => {
    // Tokens that have an ACTIVE SRUB pool (so the trade is executable),
    // excluding SRUB itself.
    const pairable = new Set<string>()
    for (const p of activePools) {
      if (p.tokenYSymbol === BASE_SYMBOL) pairable.add(p.tokenXSymbol)
      else if (p.tokenXSymbol === BASE_SYMBOL) pairable.add(p.tokenYSymbol)
    }
    return (tokenList?.content ?? []).filter(
      (t: Token) => t.symbol !== BASE_SYMBOL && pairable.has(t.symbol),
    )
  }, [tokenList, activePools])

  const selectedAsset = useMemo(
    () => (tokenList?.content ?? []).find((t: Token) => t.id === assetId) ?? null,
    [tokenList, assetId],
  )

  const assetPool = useMemo(() => {
    if (!selectedAsset) return null
    return activePools.find(
      (p: Pool) =>
        (p.tokenXSymbol === selectedAsset.symbol && p.tokenYSymbol === BASE_SYMBOL) ||
        (p.tokenYSymbol === selectedAsset.symbol && p.tokenXSymbol === BASE_SYMBOL),
    ) ?? null
  }, [activePools, selectedAsset])

  // tokenIn for the swap: BUY = pay SRUB to get the asset; SELL = pay the
  // asset to get SRUB. amount is always denominated in tokenIn units.
  const tokenInId = useMemo(() => {
    if (!assetPool || !selectedAsset) return ''
    const srubId = assetPool.tokenXSymbol === BASE_SYMBOL ? assetPool.tokenXId : assetPool.tokenYId
    return side === 'buy' ? srubId : selectedAsset.id
  }, [assetPool, selectedAsset, side])

  const tokenInSymbol = side === 'buy' ? BASE_SYMBOL : selectedAsset?.symbol
  const tokenOutSymbol = side === 'buy' ? selectedAsset?.symbol : BASE_SYMBOL
  const inBalance = tokenInSymbol ? balanceMap.get(tokenInSymbol) : undefined

  const { data: quote, isLoading: quoteLoading } = useQuery({
    queryKey: ['simpleQuote', assetPool?.id, tokenInId, amount],
    queryFn: () => pools.getSwapQuote({
      poolId: assetPool!.id,
      tokenInId,
      amountIn: amount!,
    }),
    enabled: !!assetPool && !!tokenInId && !!amount && amount > 0,
    retry: false,
  })

  const minAmountOut = quote ? Math.floor(quote.amountOut * (1 - SIMPLE_SLIPPAGE_PCT / 100)) : 0

  const tradeMutation = useMutation({
    mutationFn: () => pools.executeSwap({
      poolId: assetPool!.id,
      tokenInId,
      amountIn: amount!,
      minAmountOut,
      idempotencyKey: crypto.randomUUID(),
    }),
    onSuccess: () => {
      setTradeSuccess(
        side === 'buy'
          ? `Куплено: ${selectedAsset?.symbol}`
          : `Продано: ${selectedAsset?.symbol}`,
      )
      setTradeError(null)
      setAmount(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myTransactions'] })
      setTimeout(() => setTradeSuccess(null), 5000)
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } }
      setTradeError(e?.response?.data?.message || 'Не удалось выполнить операцию')
    },
  })

  // ── Add-liquidity: selected pool + defaults ─────────────────────────
  const selectedLpPool = useMemo(
    () => activePools.find((p: Pool) => p.id === lpPoolId) ?? null,
    [activePools, lpPoolId],
  )
  const lpBalanceX = selectedLpPool ? balanceMap.get(selectedLpPool.tokenXSymbol) : undefined
  const lpBalanceY = selectedLpPool ? balanceMap.get(selectedLpPool.tokenYSymbol) : undefined

  const addLiquidityMutation = useMutation({
    mutationFn: () => {
      const active = selectedLpPool!.activeBinId
      return pools.addLiquidity({
        poolId: selectedLpPool!.id,
        amountX: lpAmountX!,
        amountY: lpAmountY!,
        // SM-01 defaults — no bin/strategy UI in Simple mode.
        strategy: 'SPOT',
        binRangeMin: active - SIMPLE_BIN_HALF_RANGE,
        binRangeMax: active + SIMPLE_BIN_HALF_RANGE,
        idempotencyKey: crypto.randomUUID(),
      })
    },
    onSuccess: () => {
      setLpSuccess('Ликвидность добавлена по базовым настройкам')
      setLpError(null)
      setLpAmountX(null)
      setLpAmountY(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['poolDetail', lpPoolId] })
      setTimeout(() => setLpSuccess(null), 5000)
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } }
      setLpError(e?.response?.data?.message || 'Не удалось добавить ликвидность')
    },
  })

  const assetOptions = assetTokens.map((t: Token) => ({
    label: `${t.symbol} — ${t.name}`,
    value: t.id,
    symbol: t.symbol,
  }))
  const lpPoolOptions = activePools.map((p: Pool) => ({
    label: `${p.tokenXSymbol} / ${p.tokenYSymbol}`,
    value: p.id,
    x: p.tokenXSymbol,
    y: p.tokenYSymbol,
  }))

  const canTrade = !!assetPool && !!amount && amount > 0 && !!quote && !tradeMutation.isPending
  const canAddLiquidity =
    !!selectedLpPool && !!lpAmountX && lpAmountX > 0 && !!lpAmountY && lpAmountY > 0 &&
    !addLiquidityMutation.isPending

  const tradeCtaLabel = (() => {
    if (!selectedAsset) return 'Выберите актив'
    if (!assetPool) return 'Нет рынка для актива'
    if (!amount) return 'Введите сумму'
    if (tradeMutation.isPending) return side === 'buy' ? 'Покупка…' : 'Продажа…'
    return side === 'buy' ? `Купить ${selectedAsset.symbol}` : `Продать ${selectedAsset.symbol}`
  })()

  return (
    <div className="sber-simple-page">
      <div className="sber-simple-head">
        <Title level={4} className="sber-page-title" style={{ marginBottom: 'var(--space-1)' }}>
          Простой режим
        </Title>
        <Text type="secondary" style={{ fontSize: 'var(--text-sm)' }}>
          Купить, продать или вложить — без бинов и стратегий. Всё по базовым настройкам.
        </Text>
      </div>

      <div className="sber-simple-grid">
        {/* ── Card 1 — Buy / Sell (primary, Tier-2 elevation) ───────── */}
        <Card
          className={`sber-card sber-simple-card sber-simple-card--primary sber-simple-card--${side}`}
          styles={{ body: { padding: 'var(--space-6)' } }}
        >
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <div>
              <Text strong style={{ fontSize: 'var(--text-md)', display: 'block', marginBottom: 'var(--space-3)' }}>
                Купить или продать
              </Text>
              <Segmented<Side>
                block
                size="large"
                value={side}
                onChange={(v) => { setSide(v); setAmount(null); setTradeError(null) }}
                className="sber-simple-side"
                options={[
                  {
                    value: 'buy',
                    label: (
                      <span className="sber-simple-side__opt" style={{ color: 'var(--viz-up)' }}>
                        <ArrowUpOutlined /> Купить
                      </span>
                    ),
                  },
                  {
                    value: 'sell',
                    label: (
                      <span className="sber-simple-side__opt" style={{ color: 'var(--viz-down)' }}>
                        <ArrowDownOutlined /> Продать
                      </span>
                    ),
                  },
                ]}
              />
            </div>

            <div>
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)', display: 'block', marginBottom: 'var(--space-2)' }}>
                Актив
              </Text>
              <Select
                size="large"
                style={{ width: '100%' }}
                placeholder="Выберите токен"
                value={assetId || undefined}
                onChange={(v) => { setAssetId(v); setAmount(null); setTradeError(null) }}
                options={assetOptions}
                showSearch
                optionFilterProp="label"
                labelRender={({ value }) => {
                  const o = assetOptions.find((x) => x.value === value)
                  return (
                    <Space size={8} style={{ alignItems: 'center' }}>
                      <TokenChip symbol={o?.symbol} size={22} />
                      <Text strong>{o?.symbol}</Text>
                    </Space>
                  )
                }}
              />
            </div>

            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-2)' }}>
                <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                  Сумма{tokenInSymbol ? ` (${tokenInSymbol})` : ''}
                </Text>
                {inBalance && (
                  <Space size={6}>
                    <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                      Доступно:{' '}
                      <Text strong style={{ fontSize: 'var(--text-xs)', fontVariantNumeric: 'tabular-nums' }}>
                        {formatCompact(inBalance.available)}
                      </Text>
                    </Text>
                    <Button
                      type="link"
                      size="small"
                      style={{ padding: '0 var(--space-1)', fontSize: 'var(--text-xs)', height: 'auto' }}
                      onClick={() => setAmount(inBalance.available)}
                    >
                      MAX
                    </Button>
                  </Space>
                )}
              </div>
              <InputNumber
                size="large"
                style={{ width: '100%' }}
                placeholder="0.00"
                value={amount}
                onChange={(v) => setAmount(v)}
                min={0}
                controls={false}
              />
            </div>

            {/* One-line estimate. No slippage/route controls in Simple. */}
            {quoteLoading && (
              <div className="sber-simple-est sber-simple-est--loading">
                <Spin size="small" /> <Text type="secondary">Расчёт…</Text>
              </div>
            )}
            {quote && !quoteLoading && tokenOutSymbol && (
              <div className="sber-simple-est" aria-live="polite">
                <div className="sber-simple-est__row">
                  <Text type="secondary" style={{ fontSize: 'var(--text-sm)' }}>Вы получите ≈</Text>
                  <Text strong style={{ fontVariantNumeric: 'tabular-nums', color: 'var(--text-primary)' }}>
                    {formatTokenAmount(quote.amountOut, tokenOutSymbol, { compact: true, maxFractionDigits: 4 })}
                  </Text>
                </div>
                <div className="sber-simple-est__row">
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>Комиссия пула</Text>
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)', fontVariantNumeric: 'tabular-nums' }}>
                    {formatTokenAmount(quote.fee, tokenInSymbol, { compact: true, maxFractionDigits: 6 })}
                  </Text>
                </div>
              </div>
            )}

            {selectedAsset && !assetPool && (
              <Alert
                type="warning"
                showIcon
                message={`Для ${selectedAsset.symbol} нет активного рынка к ${BASE_SYMBOL}`}
                style={{ borderRadius: 'var(--radius-md)' }}
              />
            )}
            {tradeSuccess && (
              <Alert type="success" showIcon message={tradeSuccess} closable
                onClose={() => setTradeSuccess(null)} style={{ borderRadius: 'var(--radius-md)' }} />
            )}
            {tradeError && (
              <Alert type="error" showIcon message={tradeError} closable
                onClose={() => setTradeError(null)} style={{ borderRadius: 'var(--radius-md)' }} />
            )}

            <Button
              block
              size="large"
              className={`sber-simple-cta sber-simple-cta--${side}`}
              disabled={!canTrade}
              loading={tradeMutation.isPending}
              onClick={() => tradeMutation.mutate()}
              icon={side === 'buy' ? <ArrowUpOutlined /> : <ArrowDownOutlined />}
            >
              {tradeCtaLabel}
            </Button>

            <Text type="secondary" style={{ fontSize: 'var(--text-xs)', textAlign: 'center', display: 'block' }}>
              Рыночная сделка к {BASE_SYMBOL}. Допуск проскальзывания {SIMPLE_SLIPPAGE_PCT}% применяется автоматически.
            </Text>
          </Space>
        </Card>

        {/* ── Card 2 — Add liquidity (basic settings) ───────────────── */}
        <Card
          className="sber-card sber-simple-card"
          styles={{ body: { padding: 'var(--space-6)' } }}
        >
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <div>
              <Text strong style={{ fontSize: 'var(--text-md)', display: 'block', marginBottom: 'var(--space-1)' }}>
                Добавить ликвидность
              </Text>
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                Базовые настройки — зарабатывайте на комиссиях без выбора диапазона.
              </Text>
            </div>

            <div>
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)', display: 'block', marginBottom: 'var(--space-2)' }}>
                Пул
              </Text>
              <Select
                size="large"
                style={{ width: '100%' }}
                placeholder="Выберите пул"
                value={lpPoolId || undefined}
                onChange={(v) => { setLpPoolId(v); setLpAmountX(null); setLpAmountY(null); setLpError(null) }}
                options={lpPoolOptions}
                showSearch
                optionFilterProp="label"
                labelRender={({ value }) => {
                  const o = lpPoolOptions.find((x) => x.value === value)
                  return o ? <TokenPairChip x={o.x} y={o.y} size="sm" /> : null
                }}
              />
            </div>

            {selectedLpPool && (
              <>
                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-2)' }}>
                    <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{selectedLpPool.tokenXSymbol}</Text>
                    {lpBalanceX && (
                      <Space size={6}>
                        <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                          Доступно: {formatCompact(lpBalanceX.available)}
                        </Text>
                        <Button type="link" size="small" style={{ padding: '0 var(--space-1)', fontSize: 'var(--text-xs)', height: 'auto' }}
                          onClick={() => setLpAmountX(lpBalanceX.available)}>MAX</Button>
                      </Space>
                    )}
                  </div>
                  <InputNumber size="large" style={{ width: '100%' }} placeholder="0.00"
                    value={lpAmountX} onChange={(v) => setLpAmountX(v)} min={0} controls={false} />
                </div>

                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-2)' }}>
                    <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{selectedLpPool.tokenYSymbol}</Text>
                    {lpBalanceY && (
                      <Space size={6}>
                        <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                          Доступно: {formatCompact(lpBalanceY.available)}
                        </Text>
                        <Button type="link" size="small" style={{ padding: '0 var(--space-1)', fontSize: 'var(--text-xs)', height: 'auto' }}
                          onClick={() => setLpAmountY(lpBalanceY.available)}>MAX</Button>
                      </Space>
                    )}
                  </div>
                  <InputNumber size="large" style={{ width: '100%' }} placeholder="0.00"
                    value={lpAmountY} onChange={(v) => setLpAmountY(v)} min={0} controls={false} />
                </div>

                <div className="sber-simple-note">
                  <InfoCircleOutlined style={{ color: 'var(--brand-primary)' }} />
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                    по базовым настройкам (диапазон ±{SIMPLE_BIN_HALF_RANGE} бинов вокруг цены)
                  </Text>
                </div>
              </>
            )}

            {lpSuccess && (
              <Alert type="success" showIcon message={lpSuccess} closable
                onClose={() => setLpSuccess(null)} style={{ borderRadius: 'var(--radius-md)' }} />
            )}
            {lpError && (
              <Alert type="error" showIcon message={lpError} closable
                onClose={() => setLpError(null)} style={{ borderRadius: 'var(--radius-md)' }} />
            )}

            <Button
              type="primary"
              block
              size="large"
              icon={<PlusOutlined />}
              disabled={!canAddLiquidity}
              loading={addLiquidityMutation.isPending}
              onClick={() => addLiquidityMutation.mutate()}
              style={{ borderRadius: 'var(--radius-md)' }}
            >
              Добавить
            </Button>

            {/* Light context so the empty card isn't barren before a pick. */}
            {!selectedLpPool && activePools.length > 0 && (
              <>
                <Divider style={{ margin: 'var(--space-2) 0' }} />
                <Text type="secondary" style={{ fontSize: 'var(--text-xs)', textTransform: 'uppercase', letterSpacing: '0.04em', fontWeight: 500 }}>
                  Популярные пулы
                </Text>
                <div>
                  {[...activePools]
                    .sort((a: Pool, b: Pool) => (b.totalTvlX + b.totalTvlY) - (a.totalTvlX + a.totalTvlY))
                    .slice(0, 4)
                    .map((p: Pool, i, arr) => (
                      <div
                        key={p.id}
                        onClick={() => setLpPoolId(p.id)}
                        className="sber-simple-poolrow"
                        style={{ borderBottom: i < arr.length - 1 ? '1px solid var(--border-light)' : 'none' }}
                      >
                        <TokenPairChip x={p.tokenXSymbol} y={p.tokenYSymbol} size="sm" />
                        <Space size={6}>
                          <Tag color="green" style={{ marginInlineEnd: 0 }}>
                            <ThunderboltFilled style={{ fontSize: 10, marginRight: 4 }} />
                            {p.estimatedApy > 0 ? `${p.estimatedApy.toFixed(1)}% APY` : 'активен'}
                          </Tag>
                          <Text type="secondary" style={{ fontSize: 'var(--text-xs)', fontVariantNumeric: 'tabular-nums' }}>
                            {formatCompact(p.totalTvlX + p.totalTvlY)}
                          </Text>
                        </Space>
                      </div>
                    ))}
                </div>
              </>
            )}
          </Space>
        </Card>
      </div>
    </div>
  )
}
