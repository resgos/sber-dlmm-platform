import { useMemo, useState } from 'react'
import {
  Card, Select, InputNumber, Button, Typography, Space, Alert, Segmented, Spin, Divider, Tag, Empty,
} from 'antd'
import {
  ArrowUpOutlined, ArrowDownOutlined, PlusOutlined, ThunderboltFilled, InfoCircleOutlined, ExportOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { tokens, pools, balances } from '@/api/services'
import type { Token, Pool, TokenBalance, Position } from '@/api/types'
import TokenChip from '@/components/TokenChip'
import TokenSelect from '@/components/TokenSelect'
import PartialFillNotice from '@/components/PartialFillNotice'
import { rowButtonProps } from '@/lib/a11y'
import { TokenPairChip } from '@/components/sber'
import { formatCompact, formatTokenAmount, baseAnchoredRate } from '@/lib/format'
import { apiErrorMessage } from '@/lib/apiError'
import { celebrateSberkot } from '@/components/sberkot/events'
import { uuid } from '../lib/uuid'

const { Title, Text } = Typography

/**
 * SM-01 — Simple trading surface.
 *
 * A deliberately bare alternative to the Pro Swap/Liquidity pages for users
 * who just want to buy/sell or manage liquidity without thinking about bins,
 * strategies, or ranges. Two cards:
 *
 *   1. Купить / Продать  — MARKET swap against the token's SRUB pool.
 *      Reuses pools.getSwapQuote + pools.executeSwap under the hood; the
 *      Buy/Sell direction just decides which side of the SRUB pair is
 *      tokenIn. One confirm, no slippage/bins UI (a sane default slippage
 *      is applied internally).
 *
 *   2. Ликвидность — a Добавить / Забрать toggle:
 *      • Добавить: pools.addLiquidity with sensible defaults (SPOT, bin
 *        range [activeBinId-10, activeBinId+10]). No bin/strategy UI.
 *      • Забрать: pick one of your open positions + a 25/50/75/100 %
 *        and pools.removeLiquidity does the rest. No percentage slider,
 *        no per-bin maths — the headline "упрощённый забор ликвидности".
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
type LpMode = 'add' | 'remove'

export default function SimpleTradePage() {
  const { t } = useTranslation()
  const queryClient = useQueryClient()

  // ── Buy/Sell card state ────────────────────────────────────────────
  // Default to Продать the asset — consistent with the pool/standalone swap
  // panels; the user flagged that the trade surfaces opened on a purchase.
  const [side, setSide] = useState<Side>('sell')
  const [assetId, setAssetId] = useState<string>('')
  const [amount, setAmount] = useState<number | null>(null)
  const [tradeError, setTradeError] = useState<string | null>(null)
  const [tradeSuccess, setTradeSuccess] = useState<string | null>(null)

  // ── Liquidity card: Добавить / Забрать ─────────────────────────────
  const [lpMode, setLpMode] = useState<LpMode>('add')

  // Add-liquidity state
  const [lpPoolId, setLpPoolId] = useState<string>('')
  const [lpAmountX, setLpAmountX] = useState<number | null>(null)
  const [lpAmountY, setLpAmountY] = useState<number | null>(null)
  const [lpError, setLpError] = useState<string | null>(null)
  const [lpSuccess, setLpSuccess] = useState<string | null>(null)

  // Withdraw (забор) state — SM-01 v2
  const [removePositionId, setRemovePositionId] = useState<string>('')
  const [removePercent, setRemovePercent] = useState<number>(100)
  const [removeError, setRemoveError] = useState<string | null>(null)
  const [removeSuccess, setRemoveSuccess] = useState<string | null>(null)

  const { data: tokenList } = useQuery({
    queryKey: ['tokens'],
    queryFn: () => tokens.getTokens(0, 100),
  })
  const { data: myBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })
  const { data: poolList } = useQuery({
    queryKey: ['pools', 0, 100],
    queryFn: () => pools.getPools(0, 100),
  })
  // Positions power the «Забрать» tab. Same query key as PositionsPage so
  // the cache is shared and a withdraw here refreshes that page too.
  const { data: myPositions } = useQuery({
    queryKey: ['myPositions'],
    queryFn: pools.getMyPositions,
  })

  const activePools = useMemo(
    () => (poolList?.content ?? []).filter((p: Pool) => p.status === 'ACTIVE'),
    [poolList],
  )

  // poolId → Pool, for resolving token symbols on positions (the backend
  // Position DTO historically omits tokenXSymbol/tokenYSymbol).
  const poolById = useMemo(() => {
    const m = new Map<string, Pool>()
    for (const p of poolList?.content ?? []) m.set(p.id, p)
    return m
  }, [poolList])

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

  // Floor the min-received at the quoted slippage, but never let it collapse to
  // 0 for a positive quote: with whole-token amounts a tiny output rounds to 0,
  // which would mean "accept ANY output" (no protection). At least 1 unit back.
  const minAmountOut = quote && quote.amountOut > 0
    ? Math.max(1, Math.floor(quote.amountOut * (1 - SIMPLE_SLIPPAGE_PCT / 100)))
    : 0

  const tradeMutation = useMutation({
    mutationFn: () => pools.executeSwap({
      poolId: assetPool!.id,
      tokenInId,
      amountIn: amount!,
      minAmountOut,
      idempotencyKey: uuid(),
    }),
    onSuccess: () => {
      celebrateSberkot(
        side === 'buy'
          ? t('swap.simple.boughtCelebrate', { sym: selectedAsset?.symbol })
          : t('swap.simple.soldCelebrate', { sym: selectedAsset?.symbol }),
      )
      setTradeSuccess(
        side === 'buy'
          ? t('swap.simple.bought', { sym: selectedAsset?.symbol })
          : t('swap.simple.sold', { sym: selectedAsset?.symbol }),
      )
      setTradeError(null)
      setAmount(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myTransactions'] })
      setTimeout(() => setTradeSuccess(null), 5000)
    },
    onError: (err: unknown) => {
      setTradeError(apiErrorMessage(err, t('swap.simple.tradeErrorFallback')))
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
        idempotencyKey: uuid(),
      })
    },
    onSuccess: () => {
      celebrateSberkot(t('swap.simple.addCelebrate'))
      setLpSuccess(t('swap.simple.addSuccess'))
      setLpError(null)
      setLpAmountX(null)
      setLpAmountY(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['poolDetail', lpPoolId] })
      setTimeout(() => setLpSuccess(null), 5000)
    },
    onError: (err: unknown) => {
      setLpError(apiErrorMessage(err, t('swap.simple.addErrorFallback')))
    },
  })

  // ── Withdraw (забор): user's active positions ───────────────────────
  const activePositions = useMemo(
    () => (myPositions ?? []).filter((p: Position) => p.isActive),
    [myPositions],
  )
  const selectedRemovePosition = useMemo(
    () => activePositions.find((p: Position) => p.id === removePositionId) ?? null,
    [activePositions, removePositionId],
  )

  const removeMutation = useMutation({
    mutationFn: () => pools.removeLiquidity({
      positionId: removePositionId,
      percentage: removePercent,
      idempotencyKey: uuid(),
    }),
    onSuccess: () => {
      const poolId = selectedRemovePosition?.poolId
      celebrateSberkot(t('swap.simple.removeCelebrate', { percent: removePercent }))
      setRemoveSuccess(t('swap.simple.removeSuccess', { percent: removePercent }))
      setRemoveError(null)
      setRemovePositionId('')
      setRemovePercent(100)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      // Withdrawing reduces pool TVL — refresh the catalog + this pool's detail
      // so «Популярные пулы» / любой открытый PoolDetail не показывают старый TVL.
      queryClient.invalidateQueries({ queryKey: ['pools', 0, 100] })
      if (poolId) queryClient.invalidateQueries({ queryKey: ['poolDetail', poolId] })
      setTimeout(() => setRemoveSuccess(null), 5000)
    },
    onError: (err: unknown) => {
      setRemoveError(apiErrorMessage(err, t('swap.simple.removeErrorFallback')))
    },
  })

  const assetOptions = assetTokens.map((t: Token) => ({
    label: `${t.symbol} — ${t.name}`,
    value: t.id,
    symbol: t.symbol,
  }))
  const assetSelItems = assetTokens.map((t: Token) => ({
    id: t.id,
    symbol: t.symbol,
    name: t.name,
    available: balanceMap.get(t.symbol)?.available,
  }))
  const lpPoolOptions = activePools.map((p: Pool) => ({
    label: `${p.tokenXSymbol} / ${p.tokenYSymbol}`,
    value: p.id,
    x: p.tokenXSymbol,
    y: p.tokenYSymbol,
  }))
  const positionOptions = activePositions.map((p: Position) => {
    const pool = poolById.get(p.poolId)
    const x = pool?.tokenXSymbol
    const y = pool?.tokenYSymbol
    return {
      label: x && y ? `${x} / ${y}` : t('swap.simple.positionFallback', { id: p.id.slice(0, 6) }),
      value: p.id,
      x,
      y,
    }
  })

  const canTrade = !!assetPool && !!amount && amount > 0 && !!quote && !tradeMutation.isPending
  const canAddLiquidity =
    !!selectedLpPool && !!lpAmountX && lpAmountX > 0 && !!lpAmountY && lpAmountY > 0 &&
    !addLiquidityMutation.isPending
  const canRemove = !!removePositionId && !removeMutation.isPending

  // SM-01 — decode the add-liquidity "black box": current price, the actual
  // price band the ±10-bin default covers, the deposit's value, the pool fee
  // the position earns and the rough pool share. Recomputes as amounts change.
  const lpBreakdown = useMemo(() => {
    if (!selectedLpPool) return null
    const price = selectedLpPool.currentPrice ?? 0
    const ratio = 1 + (selectedLpPool.binStep ?? 0) / 10_000
    const factor = ratio > 1 ? Math.pow(ratio, SIMPLE_BIN_HALF_RANGE) : 1
    const priceLow = price > 0 && factor > 0 ? price / factor : 0
    const priceHigh = price * factor
    const rangePct = (factor - 1) * 100
    const ax = lpAmountX ?? 0
    const ay = lpAmountY ?? 0
    const depositY = ax * price + ay // total deposit valued in token Y (SRUB)
    const poolY = (selectedLpPool.totalTvlX ?? 0) * price + (selectedLpPool.totalTvlY ?? 0)
    const share = depositY > 0 && poolY + depositY > 0 ? (depositY / (poolY + depositY)) * 100 : 0
    const feePct = (selectedLpPool.baseFeeBps ?? 0) / 100
    return { price, priceLow, priceHigh, rangePct, depositY, share, feePct, hasAmount: ax > 0 || ay > 0 }
  }, [selectedLpPool, lpAmountX, lpAmountY])

  const tradeCtaLabel = (() => {
    if (!selectedAsset) return t('swap.simple.ctaSelectAsset')
    if (!assetPool) return t('swap.simple.ctaNoMarket')
    if (!amount) return t('swap.simple.ctaEnterAmount')
    if (tradeMutation.isPending) return side === 'buy' ? t('swap.simple.ctaBuying') : t('swap.simple.ctaSelling')
    return side === 'buy' ? t('swap.simple.ctaBuy', { sym: selectedAsset.symbol }) : t('swap.simple.ctaSell', { sym: selectedAsset.symbol })
  })()

  return (
    <div className="sber-simple-page">
      <div className="sber-simple-head">
        <Title level={4} className="sber-page-title" style={{ marginBottom: 'var(--space-1)' }}>
          {t('swap.simple.pageTitle')}
        </Title>
        <Text type="secondary" style={{ fontSize: 'var(--text-sm)' }}>
          {t('swap.simple.pageSubtitle')}
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
                {t('swap.simple.buyOrSell')}
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
                        <ArrowUpOutlined /> {t('swap.simple.buy')}
                      </span>
                    ),
                  },
                  {
                    value: 'sell',
                    label: (
                      <span className="sber-simple-side__opt" style={{ color: 'var(--viz-down)' }}>
                        <ArrowDownOutlined /> {t('swap.simple.sell')}
                      </span>
                    ),
                  },
                ]}
              />
            </div>

            <div>
              <Text type="secondary" style={{ fontSize: 'var(--text-xs)', display: 'block', marginBottom: 'var(--space-2)' }}>
                {t('swap.simple.asset')}
              </Text>
              <TokenSelect
                value={assetId}
                onChange={(v) => { setAssetId(v); setAmount(null); setTradeError(null) }}
                tokens={assetSelItems}
                placeholder={t('swap.simple.assetPlaceholder')}
              />
            </div>

            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-2)' }}>
                <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                  {tokenInSymbol ? t('swap.simple.amountWithSym', { sym: tokenInSymbol }) : t('swap.simple.amount')}
                </Text>
                {inBalance && (
                  <Space size={6}>
                    <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                      {t('swap.available')}:{' '}
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
                aria-label={t('swap.simple.amountAria')}
                value={amount}
                onChange={(v) => setAmount(v)}
                min={0}
                controls={false}
              />
            </div>

            {/* One-line estimate. No slippage/route controls in Simple. */}
            {quoteLoading && (
              <div className="sber-simple-est sber-simple-est--loading">
                <Spin size="small" /> <Text type="secondary">{t('swap.simple.calculating')}</Text>
              </div>
            )}
            {quote && !quoteLoading && tokenOutSymbol && (
              <div className="sber-simple-est" aria-live="polite">
                <div className="sber-simple-est__row">
                  <Text type="secondary" style={{ fontSize: 'var(--text-sm)' }}>{t('swap.simple.youReceiveApprox')}</Text>
                  <Text strong style={{ fontVariantNumeric: 'tabular-nums', color: 'var(--text-primary)' }}>
                    {formatTokenAmount(quote.amountOut, tokenOutSymbol, { compact: true, maxFractionDigits: 4 })}
                  </Text>
                </div>
                {(() => {
                  // Base = the picked asset (non-SRUB), so the rate reads as the
                  // asset's price (₽ per SETH) on BOTH Buy and Sell, not inverted.
                  const r = baseAnchoredRate(quote.amountIn, quote.amountOut, tokenInSymbol, tokenOutSymbol, selectedAsset?.symbol)
                  return r ? (
                    <div className="sber-simple-est__row" style={{ alignItems: 'flex-start' }}>
                      <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{t('swap.simple.rate')}</Text>
                      <div style={{ textAlign: 'right' }}>
                        <Text style={{ fontSize: 'var(--text-xs)', fontVariantNumeric: 'tabular-nums', display: 'block' }}>{r.forward}</Text>
                        <Text type="secondary" style={{ fontSize: 'var(--text-xs)', fontVariantNumeric: 'tabular-nums', display: 'block' }}>{r.reverse}</Text>
                      </div>
                    </div>
                  ) : null
                })()}
                <div className="sber-simple-est__row">
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{t('swap.simple.poolFee')}</Text>
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
                message={t('swap.simple.noMarket', { sym: selectedAsset.symbol, base: BASE_SYMBOL })}
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

            <PartialFillNotice fillable={quote?.amountIn} requested={amount} symbol={tokenInSymbol}
              style={{ borderRadius: 'var(--radius-md)' }} />

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
              {t('swap.simple.marketNote', { base: BASE_SYMBOL, slippage: SIMPLE_SLIPPAGE_PCT })}
            </Text>
          </Space>
        </Card>

        {/* ── Card 2 — Liquidity: Добавить / Забрать (SM-01 v2) ──────── */}
        <Card
          className="sber-card sber-simple-card"
          styles={{ body: { padding: 'var(--space-6)' } }}
        >
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            <div>
              <Text strong style={{ fontSize: 'var(--text-md)', display: 'block', marginBottom: 'var(--space-3)' }}>
                {t('swap.simple.liquidity')}
              </Text>
              <Segmented<LpMode>
                block
                size="large"
                value={lpMode}
                onChange={(v) => { setLpMode(v); setLpError(null); setRemoveError(null) }}
                options={[
                  { value: 'add', label: <span className="sber-simple-side__opt"><PlusOutlined /> {t('swap.simple.lpAdd')}</span> },
                  { value: 'remove', label: <span className="sber-simple-side__opt"><ExportOutlined /> {t('swap.simple.lpRemove')}</span> },
                ]}
              />
            </div>

            {/* ── ДОБАВИТЬ ─────────────────────────────────────────── */}
            {lpMode === 'add' && (
              <>
                <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                  {t('swap.simple.addNote')}
                </Text>

                <div>
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)', display: 'block', marginBottom: 'var(--space-2)' }}>
                    {t('swap.simple.pool')}
                  </Text>
                  <Select
                    size="large"
                    style={{ width: '100%' }}
                    aria-label={t('swap.simple.poolAria')}
                    placeholder={t('swap.simple.poolPlaceholder')}
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
                              {t('swap.available')}: {formatCompact(lpBalanceX.available)}
                            </Text>
                            <Button type="link" size="small" style={{ padding: '0 var(--space-1)', fontSize: 'var(--text-xs)', height: 'auto' }}
                              onClick={() => setLpAmountX(lpBalanceX.available)}>MAX</Button>
                          </Space>
                        )}
                      </div>
                      <InputNumber size="large" style={{ width: '100%' }} placeholder="0.00" aria-label={t('swap.simple.amountOfAria', { sym: selectedLpPool.tokenXSymbol })}
                        value={lpAmountX} onChange={(v) => setLpAmountX(v)} min={0} controls={false} />
                    </div>

                    <div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 'var(--space-2)' }}>
                        <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{selectedLpPool.tokenYSymbol}</Text>
                        {lpBalanceY && (
                          <Space size={6}>
                            <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                              {t('swap.available')}: {formatCompact(lpBalanceY.available)}
                            </Text>
                            <Button type="link" size="small" style={{ padding: '0 var(--space-1)', fontSize: 'var(--text-xs)', height: 'auto' }}
                              onClick={() => setLpAmountY(lpBalanceY.available)}>MAX</Button>
                          </Space>
                        )}
                      </div>
                      <InputNumber size="large" style={{ width: '100%' }} placeholder="0.00" aria-label={t('swap.simple.amountOfAria', { sym: selectedLpPool.tokenYSymbol })}
                        value={lpAmountY} onChange={(v) => setLpAmountY(v)} min={0} controls={false} />
                    </div>

                    {lpBreakdown && (
                      <div className="sber-simple-breakdown">
                        <span className="sber-simple-breakdown__title">
                          <InfoCircleOutlined /> {t('swap.simple.whatHappens')}
                        </span>
                        <div className="sber-simple-breakdown__row">
                          <span>{t('swap.simple.lpCurrentPrice')}</span>
                          <b>1 {selectedLpPool.tokenXSymbol} ≈ {formatCompact(lpBreakdown.price)} {selectedLpPool.tokenYSymbol}</b>
                        </div>
                        {lpBreakdown.hasAmount && (
                          <div className="sber-simple-breakdown__row">
                            <span>{t('swap.simple.youDeposit')}</span>
                            <b>{formatCompact(lpAmountX ?? 0)} {selectedLpPool.tokenXSymbol} + {formatCompact(lpAmountY ?? 0)} {selectedLpPool.tokenYSymbol} ≈ {formatCompact(lpBreakdown.depositY)} {selectedLpPool.tokenYSymbol}</b>
                          </div>
                        )}
                        <div className="sber-simple-breakdown__row">
                          <span>{t('swap.simple.workingRange')}</span>
                          <b>{formatCompact(lpBreakdown.priceLow)} – {formatCompact(lpBreakdown.priceHigh)} {selectedLpPool.tokenYSymbol} (~±{Math.round(lpBreakdown.rangePct)}%)</b>
                        </div>
                        <div className="sber-simple-breakdown__row">
                          <span>{t('swap.simple.poolFeeLp')}</span>
                          <b>{t('swap.simple.perSwap', { value: lpBreakdown.feePct.toFixed(2) })}</b>
                        </div>
                        {lpBreakdown.share > 0 && (
                          <div className="sber-simple-breakdown__row">
                            <span>{t('swap.simple.yourShare')}</span>
                            <b>≈ {lpBreakdown.share < 0.01 ? '<0.01' : lpBreakdown.share.toFixed(2)}%</b>
                          </div>
                        )}
                        <span className="sber-simple-breakdown__hint">
                          {t('swap.simple.addHint', { range: SIMPLE_BIN_HALF_RANGE })}
                        </span>
                      </div>
                    )}
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
                  {t('swap.simple.addCta')}
                </Button>

                {/* Light context so the empty card isn't barren before a pick. */}
                {!selectedLpPool && activePools.length > 0 && (
                  <>
                    <Divider style={{ margin: 'var(--space-2) 0' }} />
                    <Text type="secondary" style={{ fontSize: 'var(--text-xs)', textTransform: 'uppercase', letterSpacing: '0.04em', fontWeight: 500 }}>
                      {t('swap.simple.popularPools')}
                    </Text>
                    <div>
                      {[...activePools]
                        .sort((a: Pool, b: Pool) => (b.totalTvlX + b.totalTvlY) - (a.totalTvlX + a.totalTvlY))
                        .slice(0, 4)
                        .map((p: Pool, i, arr) => (
                          <div
                            key={p.id}
                            onClick={() => setLpPoolId(p.id)}
                            {...rowButtonProps(() => setLpPoolId(p.id), t('swap.info.pickPoolAria', { pair: `${p.tokenXSymbol} / ${p.tokenYSymbol}` }))}
                            className="sber-simple-poolrow"
                            style={{ borderBottom: i < arr.length - 1 ? '1px solid var(--border-light)' : 'none' }}
                          >
                            <TokenPairChip x={p.tokenXSymbol} y={p.tokenYSymbol} size="sm" />
                            <Space size={6}>
                              <Tag color="green" style={{ marginInlineEnd: 0 }}>
                                <ThunderboltFilled style={{ fontSize: 10, marginRight: 4 }} />
                                {p.estimatedApy > 0 ? t('swap.simple.apyTag', { value: p.estimatedApy.toFixed(1) }) : t('swap.simple.activeTag')}
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
              </>
            )}

            {/* ── ЗАБРАТЬ ──────────────────────────────────────────── */}
            {lpMode === 'remove' && (
              <>
                <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                  {t('swap.simple.removeNote')}
                </Text>

                {activePositions.length === 0 ? (
                  <Empty
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                    description={<Text type="secondary" style={{ fontSize: 'var(--text-sm)' }}>{t('swap.simple.noPositions')}</Text>}
                    style={{ margin: 'var(--space-5) 0' }}
                  />
                ) : (
                  <>
                    <div>
                      <Text type="secondary" style={{ fontSize: 'var(--text-xs)', display: 'block', marginBottom: 'var(--space-2)' }}>
                        {t('swap.simple.position')}
                      </Text>
                      <Select
                        size="large"
                        style={{ width: '100%' }}
                        aria-label={t('swap.simple.positionAria')}
                        placeholder={t('swap.simple.positionPlaceholder')}
                        value={removePositionId || undefined}
                        onChange={(v) => { setRemovePositionId(v); setRemoveError(null) }}
                        options={positionOptions}
                        showSearch
                        optionFilterProp="label"
                        labelRender={({ value }) => {
                          const o = positionOptions.find((x) => x.value === value)
                          return o && o.x && o.y ? <TokenPairChip x={o.x} y={o.y} size="sm" /> : (o?.label ?? null)
                        }}
                      />
                    </div>

                    {selectedRemovePosition && (
                      <>
                        <div>
                          <Text type="secondary" style={{ fontSize: 'var(--text-xs)', display: 'block', marginBottom: 'var(--space-2)' }}>
                            {t('swap.simple.howMuch')}
                          </Text>
                          <Segmented<number>
                            block
                            value={removePercent}
                            onChange={(v) => setRemovePercent(Number(v))}
                            options={[
                              { value: 25, label: '25%' },
                              { value: 50, label: '50%' },
                              { value: 75, label: '75%' },
                              { value: 100, label: '100%' },
                            ]}
                          />
                        </div>

                        {(() => {
                          const pool = poolById.get(selectedRemovePosition.poolId)
                          const xSym = pool?.tokenXSymbol ?? ''
                          const ySym = pool?.tokenYSymbol ?? ''
                          const f = removePercent / 100
                          const gx = (selectedRemovePosition.currentValueX ?? 0) * f
                          const gy = (selectedRemovePosition.currentValueY ?? 0) * f
                          return (
                            <div className="sber-simple-est" aria-live="polite">
                              <div className="sber-simple-est__row">
                                <Text type="secondary" style={{ fontSize: 'var(--text-sm)' }}>{t('swap.simple.returnsToBalance')}</Text>
                                <Text strong style={{ fontVariantNumeric: 'tabular-nums', color: 'var(--text-primary)' }}>
                                  {formatTokenAmount(gx, xSym, { compact: true, maxFractionDigits: 4 })}
                                  {ySym ? ` + ${formatTokenAmount(gy, ySym, { compact: true, maxFractionDigits: 4 })}` : ''}
                                </Text>
                              </div>
                              <Text type="secondary" style={{ fontSize: 'var(--text-xs)', display: 'block', marginTop: 'var(--space-1)' }}>
                                {t('swap.simple.estimateNote')}
                              </Text>
                            </div>
                          )
                        })()}
                      </>
                    )}

                    {removeSuccess && (
                      <Alert type="success" showIcon message={removeSuccess} closable
                        onClose={() => setRemoveSuccess(null)} style={{ borderRadius: 'var(--radius-md)' }} />
                    )}
                    {removeError && (
                      <Alert type="error" showIcon message={removeError} closable
                        onClose={() => setRemoveError(null)} style={{ borderRadius: 'var(--radius-md)' }} />
                    )}

                    <Button
                      type="primary"
                      block
                      size="large"
                      icon={<ExportOutlined />}
                      disabled={!canRemove}
                      loading={removeMutation.isPending}
                      onClick={() => removeMutation.mutate()}
                      style={{ borderRadius: 'var(--radius-md)' }}
                    >
                      {selectedRemovePosition ? t('swap.simple.removeCta', { percent: removePercent }) : t('swap.simple.selectPositionCta')}
                    </Button>
                  </>
                )}
              </>
            )}
          </Space>
        </Card>
      </div>
    </div>
  )
}
