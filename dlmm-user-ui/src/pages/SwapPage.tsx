import { useMemo, useState } from 'react'
import { Card, Select, InputNumber, Button, Typography, Space, Alert, Spin, Popover, Tag, Divider, Row, Col, Tooltip } from 'antd'
import {
  SettingOutlined,
  ArrowDownOutlined,
  ThunderboltFilled,
  RiseOutlined,
  FundOutlined,
  PercentageOutlined,
  InfoCircleOutlined,
  DollarOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { tokens, pools, balances } from '@/api/services'
import type { Token, Pool, TokenBalance } from '@/api/types'
import TokenChip from '@/components/TokenChip'
import { bpsToPercent } from '@/utils/format'
import { TokenPairChip } from '@/components/sber'
import { formatCompact, formatTokenAmount } from '@/lib/format'

const { Title, Text } = Typography

const SLIPPAGE_OPTIONS = [0.1, 0.5, 1.0]

// Sprint 7 dedup — TokenChip + pairAccent extracted to @/components/TokenChip.
// Was copy-pasted between SwapPage (here) and PoolsPage with an admission
// comment "Same accent function as PoolsPage — keeps token chips consistent".

export default function SwapPage() {
  // Sprint 8 C-4 — translation wiring. Only some strings extracted in this
  // first wave to keep the diff readable; full extraction is Sprint 9 work.
  // The t() call pattern here is the template for the rest of the app.
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [tokenInId, setTokenInId] = useState<string>('')
  const [tokenOutId, setTokenOutId] = useState<string>('')
  const [amountIn, setAmountIn] = useState<number | null>(null)
  const [slippage, setSlippage] = useState(0.5)
  const [customSlippage, setCustomSlippage] = useState<number | null>(null)
  const [swapError, setSwapError] = useState<string | null>(null)
  const [swapSuccess, setSwapSuccess] = useState(false)

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

  const selectedPool = useMemo(() => {
    if (!tokenInId || !tokenOutId || !poolList?.content) return null
    return poolList.content
      .filter((p: Pool) => p.status === 'ACTIVE')
      .find((p: Pool) =>
        (p.tokenXId === tokenInId && p.tokenYId === tokenOutId) ||
        (p.tokenXId === tokenOutId && p.tokenYId === tokenInId),
      ) || null
  }, [tokenInId, tokenOutId, poolList])

  const { data: quote, isLoading: quoteLoading } = useQuery({
    queryKey: ['swapQuote', selectedPool?.id, tokenInId, amountIn],
    queryFn: () => pools.getSwapQuote({
      poolId: selectedPool!.id,
      tokenInId,
      amountIn: amountIn!,
    }),
    enabled: !!selectedPool && !!tokenInId && !!amountIn && amountIn > 0,
    retry: false,
  })

  const effectiveSlippage = customSlippage ?? slippage
  const minAmountOut = quote ? Math.floor(quote.amountOut * (1 - effectiveSlippage / 100)) : 0

  const swapMutation = useMutation({
    mutationFn: () => pools.executeSwap({
      poolId: selectedPool!.id,
      tokenInId,
      amountIn: amountIn!,
      minAmountOut,
      idempotencyKey: crypto.randomUUID(),
    }),
    onSuccess: () => {
      setSwapSuccess(true)
      setSwapError(null)
      setAmountIn(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myTransactions'] })
      setTimeout(() => setSwapSuccess(false), 5000)
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } }
      setSwapError(e?.response?.data?.message || 'Ошибка при выполнении обмена')
    },
  })

  const tokenOptions = (tokenList?.content || []).map((t: Token) => ({
    label: `${t.symbol} — ${t.name}`,
    value: t.id,
    symbol: t.symbol,
  }))

  const balanceMap = new Map((myBalances || []).map((b: TokenBalance) => [b.tokenId, b]))
  const inBalance = balanceMap.get(tokenInId)

  const tokenIn = tokenList?.content?.find((t: Token) => t.id === tokenInId)
  const tokenOut = tokenList?.content?.find((t: Token) => t.id === tokenOutId)
  const tokenInSymbol = tokenIn?.symbol
  const tokenOutSymbol = tokenOut?.symbol

  const handleSwapDirection = () => {
    setTokenInId(tokenOutId)
    setTokenOutId(tokenInId)
    setAmountIn(null)
  }

  const priceImpactColor = !quote
    ? undefined
    : quote.priceImpact < 1 ? 'var(--sber-green)'
    : quote.priceImpact < 5 ? 'var(--sber-amber)'
    : '#EF4444'

  const slippageMenu = (
    <div style={{ padding: 4, minWidth: 240 }}>
      <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 8 }}>
        Допуск проскальзывания
      </Text>
      <Space size={6} style={{ marginBottom: 8 }}>
        {SLIPPAGE_OPTIONS.map((opt) => (
          <Button
            key={opt}
            type={slippage === opt && !customSlippage ? 'primary' : 'default'}
            size="small"
            onClick={() => { setSlippage(opt); setCustomSlippage(null) }}
          >
            {opt}%
          </Button>
        ))}
        <InputNumber
          size="small"
          placeholder="свой"
          style={{ width: 80 }}
          min={0.01}
          max={50}
          step={0.1}
          value={customSlippage}
          onChange={(v) => setCustomSlippage(v)}
          suffix="%"
        />
      </Space>
    </div>
  )

  // Render a single "box" — either the IN side or the OUT side.
  const renderBox = (opts: {
    label: string
    selectedTokenId: string
    onSelectToken: (id: string) => void
    excludeId: string
    value: number | null
    onValueChange?: (v: number | null) => void
    showBalance?: boolean
    readOnly?: boolean
    symbol?: string
  }) => (
    <div className="sber-swap-box">
      <div className="sber-swap-box__head">
        <Text type="secondary" style={{ fontSize: 12, fontWeight: 500 }}>{opts.label}</Text>
        {opts.showBalance && inBalance && (
          <Space size={6} style={{ alignItems: 'center' }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              Доступно:{' '}
              <Text strong style={{ fontSize: 12, fontVariantNumeric: 'tabular-nums' }}>
                {inBalance.available.toLocaleString('ru-RU')}
              </Text>
            </Text>
            {/* Sprint 9 — quick-percent shortcuts. The MAX-only button
                left every smaller trade as a typing exercise. 25/50/75
                covers the common "rebalance a fraction" flow, MAX
                stays for "exit position". Floor so we don't accidentally
                send 100.0000001% and fail the balance check. */}
            {[0.25, 0.5, 0.75].map((pct) => (
              <Button
                key={pct}
                type="text"
                size="small"
                style={{
                  padding: '0 6px',
                  fontSize: 11,
                  height: 22,
                  color: 'var(--sber-green)',
                  fontWeight: 600,
                }}
                onClick={() => setAmountIn(Math.floor(inBalance.available * pct))}
              >
                {Math.round(pct * 100)}%
              </Button>
            ))}
            <Button
              type="text"
              size="small"
              style={{
                padding: '0 6px',
                fontSize: 11,
                height: 22,
                color: 'var(--sber-green)',
                fontWeight: 700,
                letterSpacing: 0.3,
              }}
              onClick={() => setAmountIn(inBalance.available)}
            >
              MAX
            </Button>
          </Space>
        )}
      </div>
      <div className="sber-swap-box__row">
        <Select
          className="sber-swap-tokenpick"
          placeholder="Токен"
          value={opts.selectedTokenId || undefined}
          onChange={opts.onSelectToken}
          options={tokenOptions.filter((o) => o.value !== opts.excludeId)}
          showSearch
          optionFilterProp="label"
          variant="borderless"
          suffixIcon={null}
          labelRender={({ value }) => {
            const t = tokenOptions.find((o) => o.value === value)
            return (
              <Space size={8} style={{ alignItems: 'center' }}>
                <TokenChip symbol={t?.symbol} />
                <Text strong>{t?.symbol}</Text>
              </Space>
            )
          }}
        />
        <InputNumber
          className="sber-swap-amount"
          placeholder="0.0"
          value={opts.value}
          onChange={opts.onValueChange}
          min={0}
          controls={false}
          variant="borderless"
          disabled={opts.readOnly}
        />
      </div>
    </div>
  )

  return (
    <div className="sber-swap-layout">
      <div className="sber-swap-shell">
      <div className="sber-swap-headerline">
        <div>
          <Title level={4} className="sber-page-title" style={{ marginBottom: 4 }}>{t('swap.title')}</Title>
          <Text type="secondary">{t('swap.subtitle')}</Text>
        </div>
        <Popover content={slippageMenu} trigger="click" placement="bottomRight">
          <Button
            shape="circle"
            icon={<SettingOutlined aria-hidden />}
            size="large"
            aria-label={t('swap.settings')}
            aria-haspopup="dialog"
          />
        </Popover>
      </div>

      {swapSuccess && (
        <Alert message={t('swap.alerts.success')} type="success" showIcon closable
          onClose={() => setSwapSuccess(false)} style={{ marginBottom: 16, borderRadius: 12 }} />
      )}
      {swapError && (
        <Alert message={swapError} type="error" showIcon closable
          onClose={() => setSwapError(null)} style={{ marginBottom: 16, borderRadius: 12 }} />
      )}

      <Card className="sber-swap-card" styles={{ body: { padding: 0 } }}>
        <div className="sber-swap-card__body">
          {renderBox({
            label: 'Вы отдаёте',
            selectedTokenId: tokenInId,
            onSelectToken: setTokenInId,
            excludeId: tokenOutId,
            value: amountIn,
            onValueChange: (v) => setAmountIn(v),
            showBalance: true,
          })}

          <div className="sber-swap-flip">
            <button
              type="button"
              className="sber-swap-flip__btn"
              onClick={handleSwapDirection}
              aria-label="Поменять направление"
            >
              <ArrowDownOutlined />
            </button>
          </div>

          {renderBox({
            label: 'Вы получаете',
            selectedTokenId: tokenOutId,
            onSelectToken: setTokenOutId,
            excludeId: tokenInId,
            value: quote?.amountOut ?? null,
            readOnly: true,
          })}

          {/* Quote summary — collapsed metadata panel */}
          {quoteLoading && (
            <div className="sber-swap-quote sber-swap-quote--loading">
              <Spin size="small" /> <Text type="secondary">Расчёт маршрута…</Text>
            </div>
          )}
          {quote && !quoteLoading && (
            <div
              className="sber-swap-quote"
              role="region"
              aria-label="Параметры обмена"
              aria-live="polite"
            >
              <div className="sber-swap-quote__row">
                <Text type="secondary">Курс</Text>
                <Text strong>
                  1 {tokenInSymbol} ≈ {(quote.amountOut / quote.amountIn).toFixed(6)} {tokenOutSymbol}
                </Text>
              </div>
              <div className="sber-swap-quote__row">
                <Text type="secondary">Влияние на цену</Text>
                <Text strong style={{ color: priceImpactColor }}>{quote.priceImpact.toFixed(2)}%</Text>
              </div>
              <div className="sber-swap-quote__row">
                <Text type="secondary">Комиссия</Text>
                <Text>{quote.fee.toLocaleString('ru-RU')} {tokenInSymbol}</Text>
              </div>
              <div className="sber-swap-quote__row">
                <Text type="secondary">Мин. к получению</Text>
                <Text>{minAmountOut.toLocaleString('ru-RU')} {tokenOutSymbol}</Text>
              </div>
              {selectedPool && (
                <div className="sber-swap-quote__route">
                  <Tag color="green" style={{ borderRadius: 999, padding: '2px 10px' }}>
                    <ThunderboltFilled style={{ fontSize: 10, marginRight: 4 }} />
                    через пул {selectedPool.tokenXSymbol}/{selectedPool.tokenYSymbol}
                  </Tag>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    комиссия {bpsToPercent(selectedPool.baseFeeBps)} · допуск {effectiveSlippage}%
                  </Text>
                </div>
              )}
            </div>
          )}

          {!selectedPool && tokenInId && tokenOutId && (
            <Alert message={t('swap.alerts.noPool')} type="warning" showIcon
              style={{ borderRadius: 12 }} />
          )}

          <Divider style={{ margin: '4px 0' }} />

          <Button
            type="primary"
            block
            size="large"
            className="sber-swap-cta"
            disabled={!quote || !selectedPool || swapMutation.isPending}
            loading={swapMutation.isPending}
            onClick={() => swapMutation.mutate()}
          >
            {!tokenInId || !tokenOutId
              ? t('swap.cta.selectTokens')
              : !amountIn
              ? t('swap.cta.enterAmount')
              : !selectedPool
              ? t('swap.cta.noPool')
              : swapMutation.isPending
              ? t('swap.cta.executing')
              : t('swap.cta.swap')}
          </Button>
        </div>
      </Card>
      </div>

      {/* Sprint 9-DS — right info panel.
          Previously the swap card was alone on a huge empty canvas; now
          we surface market context (pool stats, recent quote, fee curve)
          so the page reads as more than a single one-shot form. */}
      <aside className="sber-swap-info">
        <SwapInfoPanel
          tokenIn={tokenIn ?? null}
          tokenOut={tokenOut ?? null}
          pool={selectedPool}
          quote={quote ?? null}
          amountIn={amountIn}
          minAmountOut={minAmountOut}
          effectiveSlippage={effectiveSlippage}
        />
      </aside>
    </div>
  )
}

interface SwapInfoPanelProps {
  tokenIn: Token | null
  tokenOut: Token | null
  pool: Pool | null
  quote: { amountIn: number; amountOut: number; fee: number; priceImpact: number } | null
  amountIn: number | null
  minAmountOut: number
  effectiveSlippage: number
}

function SwapInfoPanel({
  tokenIn,
  tokenOut,
  pool,
  quote,
  amountIn,
  minAmountOut,
  effectiveSlippage,
}: SwapInfoPanelProps) {
  if (!tokenIn && !tokenOut) {
    return (
      <Card className="sber-card" style={{ borderRadius: 16, border: '1px solid var(--border-light)' }}>
        <Space direction="vertical" size={12} align="center" style={{ width: '100%', padding: '20px 0' }}>
          <div
            aria-hidden
            style={{
              width: 56, height: 56, borderRadius: 14,
              background: 'rgba(33,160,56,0.10)', color: 'var(--sber-green)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: 24,
            }}
          >
            <InfoCircleOutlined />
          </div>
          <Text strong style={{ fontSize: 14 }}>Готовы к обмену?</Text>
          <Text type="secondary" style={{ fontSize: 12, textAlign: 'center' }}>
            Выберите пару токенов слева — здесь появятся параметры маршрута,
            рыночный контекст и предварительная оценка влияния на цену.
          </Text>
        </Space>
      </Card>
    )
  }

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {/* Pair header */}
      {tokenIn && tokenOut && (
        <Card
          className="sber-card"
          style={{
            borderRadius: 16,
            border: '1px solid var(--border-light)',
            background: 'linear-gradient(135deg, rgba(33,160,56,0.08) 0%, rgba(33,160,56,0.02) 100%)',
          }}
          styles={{ body: { padding: 16 } }}
        >
          <Space direction="vertical" size={6} style={{ width: '100%' }}>
            <TokenPairChip x={tokenIn.symbol} y={tokenOut.symbol} size="lg" />
            {pool ? (
              <Space size={6} wrap>
                <Tag color="green" style={{ borderRadius: 999, fontSize: 11 }}>
                  <ThunderboltFilled style={{ fontSize: 10, marginRight: 4 }} />
                  активный пул
                </Tag>
                <Text type="secondary" style={{ fontSize: 11 }}>
                  шаг {bpsToPercent(pool.binStep)} · комиссия {bpsToPercent(pool.baseFeeBps)}
                </Text>
              </Space>
            ) : (
              <Text type="warning" style={{ fontSize: 12 }}>
                Прямого пула для этой пары нет
              </Text>
            )}
          </Space>
        </Card>
      )}

      {/* Pool stats */}
      {pool && (
        <Card
          className="sber-card"
          style={{ borderRadius: 16, border: '1px solid var(--border-light)' }}
          styles={{ body: { padding: 16 } }}
        >
          <Text type="secondary" style={{ fontSize: 11, letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: 500 }}>
            Параметры пула
          </Text>
          <div style={{ marginTop: 10 }}>
            <InfoRow
              icon={<DollarOutlined style={{ color: 'var(--sber-green)' }} />}
              label="Текущая цена"
              value={`${pool.currentPrice.toLocaleString('ru-RU', { maximumFractionDigits: 6 })} ${pool.tokenYSymbol}/${pool.tokenXSymbol}`}
            />
            <InfoRow
              icon={<FundOutlined style={{ color: '#296AE3' }} />}
              label="Резерв"
              value={`${formatCompact(pool.totalTvlX)} ${pool.tokenXSymbol} · ${formatCompact(pool.totalTvlY)} ${pool.tokenYSymbol}`}
            />
            <InfoRow
              icon={<RiseOutlined style={{ color: '#9B59B6' }} />}
              label="Объём 24ч"
              value={formatCompact(pool.volume24h ?? 0)}
            />
            <InfoRow
              icon={<PercentageOutlined style={{ color: 'var(--sber-green)' }} />}
              label="Расч. APY"
              value={pool.estimatedApy > 0 ? `${pool.estimatedApy.toFixed(2)}%` : '—'}
              last
            />
          </div>
        </Card>
      )}

      {/* Quote breakdown — only when there's a quote */}
      {quote && tokenIn && tokenOut && amountIn && (
        <Card
          className="sber-card"
          style={{ borderRadius: 16, border: '1px solid var(--border-light)' }}
          styles={{ body: { padding: 16 } }}
        >
          <Text type="secondary" style={{ fontSize: 11, letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: 500 }}>
            Предварительный расчёт
          </Text>
          <div style={{ marginTop: 12 }}>
            <Row gutter={[8, 8]}>
              <Col span={12}>
                <Text type="secondary" style={{ fontSize: 11 }}>Вы отдаёте</Text>
                <Tooltip title={`${amountIn.toLocaleString('ru-RU')} ${tokenIn.symbol}`}>
                  <div style={{ fontSize: 16, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
                    {formatTokenAmount(amountIn, tokenIn.symbol, { compact: true, maxFractionDigits: 4 })}
                  </div>
                </Tooltip>
              </Col>
              <Col span={12} style={{ textAlign: 'right' }}>
                <Text type="secondary" style={{ fontSize: 11 }}>Получите</Text>
                <Tooltip title={`${quote.amountOut.toLocaleString('ru-RU')} ${tokenOut.symbol}`}>
                  <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--sber-green-deep)', fontVariantNumeric: 'tabular-nums' }}>
                    {formatTokenAmount(quote.amountOut, tokenOut.symbol, { compact: true, maxFractionDigits: 4 })}
                  </div>
                </Tooltip>
              </Col>
            </Row>

            <Divider style={{ margin: '12px 0' }} />

            <InfoRow
              label="Эффективный курс"
              value={`1 ${tokenIn.symbol} ≈ ${(quote.amountOut / quote.amountIn).toFixed(6)} ${tokenOut.symbol}`}
            />
            <InfoRow
              label="Влияние на цену"
              value={`${quote.priceImpact.toFixed(2)}%`}
              valueColour={
                quote.priceImpact < 0.5 ? 'var(--sber-green)'
                  : quote.priceImpact < 2 ? '#D97706'
                  : '#DC2626'
              }
            />
            <InfoRow
              label="Комиссия пула"
              value={formatTokenAmount(quote.fee, tokenIn.symbol, { compact: true, maxFractionDigits: 6 })}
            />
            <InfoRow
              label={`Мин. к получению (${effectiveSlippage}%)`}
              value={formatTokenAmount(minAmountOut, tokenOut.symbol, { compact: true, maxFractionDigits: 4 })}
              last
            />
          </div>
        </Card>
      )}
    </Space>
  )
}

function InfoRow({
  icon,
  label,
  value,
  valueColour,
  last,
}: {
  icon?: React.ReactNode
  label: string
  value: string
  valueColour?: string
  last?: boolean
}) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        padding: '6px 0',
        borderBottom: last ? 'none' : '1px solid var(--border-light)',
        gap: 12,
      }}
    >
      <Space size={6} style={{ flex: 1, minWidth: 0 }}>
        {icon}
        <Text type="secondary" style={{ fontSize: 12 }}>{label}</Text>
      </Space>
      <Text
        strong
        style={{
          fontSize: 13,
          color: valueColour,
          fontVariantNumeric: 'tabular-nums',
          textAlign: 'right',
          whiteSpace: 'nowrap',
        }}
      >
        {value}
      </Text>
    </div>
  )
}
