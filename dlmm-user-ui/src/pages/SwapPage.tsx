import { useMemo, useState } from 'react'
import { Card, Select, InputNumber, Button, Typography, Space, Alert, Spin, Popover, Tag, Divider } from 'antd'
import { SettingOutlined, ArrowDownOutlined, ThunderboltFilled } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { tokens, pools, balances } from '@/api/services'
import type { Token, Pool, TokenBalance } from '@/api/types'
import TokenChip from '@/components/TokenChip'
import { bpsToPercent } from '@/utils/format'

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

  const tokenInSymbol = tokenList?.content?.find((t: Token) => t.id === tokenInId)?.symbol
  const tokenOutSymbol = tokenList?.content?.find((t: Token) => t.id === tokenOutId)?.symbol

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
  )
}
