import { useState, useEffect, useMemo } from 'react'
import { Card, Select, InputNumber, Button, Typography, Space, Alert, Divider, Spin, Collapse } from 'antd'
import { SwapOutlined, SettingOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { tokens, pools, balances } from '@/api/services'
import type { Token, Pool, TokenBalance, SwapQuote } from '@/api/types'
import { formatRub } from '@/components/StatCard'

const { Title, Text } = Typography

const SLIPPAGE_OPTIONS = [0.1, 0.5, 1.0]

export default function SwapPage() {
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

  // Find best pool for selected pair
  const selectedPool = useMemo(() => {
    if (!tokenInId || !tokenOutId || !poolList?.content) return null
    return poolList.content
      .filter((p: Pool) => p.status === 'ACTIVE')
      .find(
        (p: Pool) =>
          (p.tokenXId === tokenInId && p.tokenYId === tokenOutId) ||
          (p.tokenXId === tokenOutId && p.tokenYId === tokenInId),
      ) || null
  }, [tokenInId, tokenOutId, poolList])

  // Get quote
  const { data: quote, isLoading: quoteLoading, error: quoteError } = useQuery({
    queryKey: ['swapQuote', selectedPool?.id, tokenInId, amountIn],
    queryFn: () =>
      pools.getSwapQuote({
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
    mutationFn: () =>
      pools.executeSwap({
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
    onError: (err: any) => {
      setSwapError(err?.response?.data?.message || 'Ошибка при выполнении обмена')
    },
  })

  const tokenOptions = (tokenList?.content || []).map((t: Token) => ({
    label: `${t.symbol} — ${t.name}`,
    value: t.id,
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

  return (
    <div style={{ maxWidth: 480, margin: '0 auto' }}>
      <Title level={4} className="sber-page-title" style={{ textAlign: 'center', marginBottom: 24 }}>
        Обмен токенов
      </Title>

      {swapSuccess && (
        <Alert message="Обмен выполнен успешно!" type="success" showIcon closable
          onClose={() => setSwapSuccess(false)} style={{ marginBottom: 16, borderRadius: 8 }} />
      )}
      {swapError && (
        <Alert message={swapError} type="error" showIcon closable
          onClose={() => setSwapError(null)} style={{ marginBottom: 16, borderRadius: 8 }} />
      )}

      <Card className="sber-card" styles={{ body: { padding: 24 } }}>
        {/* Token In */}
        <div style={{ marginBottom: 8 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
            <Text strong>Вы отдаёте</Text>
            {inBalance && (
              <Text type="secondary" style={{ fontSize: 12 }}>
                Доступно: {inBalance.available.toLocaleString('ru-RU')}
                <Button type="link" size="small" style={{ padding: '0 4px', fontSize: 12 }}
                  onClick={() => setAmountIn(inBalance.available)}>MAX</Button>
              </Text>
            )}
          </div>
          <Space.Compact style={{ width: '100%' }}>
            <Select
              style={{ width: '45%' }}
              placeholder="Токен"
              value={tokenInId || undefined}
              onChange={setTokenInId}
              options={tokenOptions.filter((o: { value: string }) => o.value !== tokenOutId)}
              showSearch
              optionFilterProp="label"
              size="large"
            />
            <InputNumber
              style={{ width: '55%' }}
              placeholder="0.00"
              value={amountIn}
              onChange={(v) => setAmountIn(v)}
              min={0}
              size="large"
              controls={false}
            />
          </Space.Compact>
        </div>

        {/* Swap direction button */}
        <div style={{ textAlign: 'center', margin: '12px 0' }}>
          <Button
            shape="circle"
            icon={<SwapOutlined rotate={90} />}
            onClick={handleSwapDirection}
            style={{ border: '2px solid #E5E7EB' }}
          />
        </div>

        {/* Token Out */}
        <div style={{ marginBottom: 16 }}>
          <Text strong style={{ display: 'block', marginBottom: 8 }}>Вы получаете</Text>
          <Space.Compact style={{ width: '100%' }}>
            <Select
              style={{ width: '45%' }}
              placeholder="Токен"
              value={tokenOutId || undefined}
              onChange={setTokenOutId}
              options={tokenOptions.filter((o: { value: string }) => o.value !== tokenInId)}
              showSearch
              optionFilterProp="label"
              size="large"
            />
            <InputNumber
              style={{ width: '55%' }}
              placeholder="0.00"
              value={quote?.amountOut ?? null}
              disabled
              size="large"
              controls={false}
            />
          </Space.Compact>
        </div>

        {/* Quote info */}
        {quoteLoading && <div style={{ textAlign: 'center', padding: 12 }}><Spin size="small" /> Расчёт...</div>}

        {quote && !quoteLoading && (
          <div style={{
            background: '#F9FAFB', borderRadius: 8, padding: 12, marginBottom: 16, fontSize: 13,
          }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
              <Text type="secondary">Курс</Text>
              <Text>1 {tokenInSymbol} = {(quote.amountOut / quote.amountIn).toFixed(6)} {tokenOutSymbol}</Text>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
              <Text type="secondary">Влияние на цену</Text>
              <Text style={{
                color: quote.priceImpact < 1 ? '#21A038' : quote.priceImpact < 5 ? '#F59E0B' : '#EF4444',
                fontWeight: 600,
              }}>
                {quote.priceImpact.toFixed(2)}%
              </Text>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
              <Text type="secondary">Комиссия</Text>
              <Text>{quote.fee.toLocaleString('ru-RU')}</Text>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <Text type="secondary">Мин. получаемая сумма</Text>
              <Text>{minAmountOut.toLocaleString('ru-RU')} {tokenOutSymbol}</Text>
            </div>
          </div>
        )}

        {selectedPool && (
          <div style={{ fontSize: 12, color: '#9CA3AF', marginBottom: 12 }}>
            Пул: {selectedPool.tokenXSymbol}/{selectedPool.tokenYSymbol} (комиссия {selectedPool.baseFeeBps} bps)
          </div>
        )}

        {!selectedPool && tokenInId && tokenOutId && (
          <Alert message="Нет активного пула для выбранной пары" type="warning" showIcon
            style={{ marginBottom: 12, borderRadius: 8 }} />
        )}

        {/* Slippage settings */}
        <Collapse
          ghost
          items={[{
            key: '1',
            label: <Space><SettingOutlined /> <Text type="secondary" style={{ fontSize: 13 }}>Допуск проскальзывания: {effectiveSlippage}%</Text></Space>,
            children: (
              <Space>
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
                  placeholder="Custom"
                  style={{ width: 80 }}
                  min={0.01}
                  max={50}
                  step={0.1}
                  value={customSlippage}
                  onChange={(v) => setCustomSlippage(v)}
                  suffix="%"
                />
              </Space>
            ),
          }]}
        />

        <Divider style={{ margin: '12px 0' }} />

        <Button
          type="primary"
          block
          size="large"
          style={{ height: 52, fontSize: 16, fontWeight: 600, borderRadius: 10 }}
          disabled={!quote || !selectedPool || swapMutation.isPending}
          loading={swapMutation.isPending}
          onClick={() => swapMutation.mutate()}
        >
          {swapMutation.isPending ? 'Выполняется обмен...' : 'Обменять'}
        </Button>
      </Card>
    </div>
  )
}
