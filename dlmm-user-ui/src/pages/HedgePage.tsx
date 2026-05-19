import { useMemo, useState } from 'react'
import {
  Card,
  Typography,
  Select,
  InputNumber,
  Button,
  Space,
  Alert,
  Tag,
  Row,
  Col,
  Statistic,
  Spin,
  Tooltip,
  Table,
  Modal,
} from 'antd'
import {
  ThunderboltFilled,
  RiseOutlined,
  FallOutlined,
  InfoCircleOutlined,
  SafetyCertificateOutlined,
  RollbackOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { pools, tokens, balances, transactions } from '@/api/services'
import type { Pool, Token, TokenBalance, Transaction } from '@/api/types'
import { bpsToPercent } from '@/utils/format'

const { Title, Text, Paragraph } = Typography

/**
 * Sprint 4 #4.1 — FX hedging UI for corp users on user-ui.
 *
 * Reuses the existing swap pipeline (no new backend endpoints) — a "hedge"
 * is structurally just a SRUB→foreign-FX swap, but framed and constrained
 * so a treasurer can pick "I want to hedge 50% of my RUB exposure for the
 * next quarter against USD" without thinking about pools or bin steps.
 *
 * Hedge candidates are picked by intersecting:
 *   - tokens with type FIAT_BACKED (seed catalog: SUSD/SEUR/SCNY/etc)
 *   - against the user's SRUB balance as the base exposure
 *   - via an existing ACTIVE pool (no point listing pairs we can't route)
 *
 * If product later wants commodity hedges (SXAU/SXAG) the FIAT_BACKED
 * filter widens to FIAT_BACKED|COMMODITY_BACKED with a tab toggle.
 */

// Base currency for hedge calculator. The whole UX is framed around
// "I have N rubles, I want to hedge X% of them" — SRUB is the anchor.
const BASE_SYMBOL = 'SRUB'

// Sprint 5 #5.14 — idempotency-key prefixes so we can find hedges
// (open + closed) later via /transactions/me filter without adding a
// new column to the transactions table. Pattern: `hedge-{uuid}` for the
// open leg, `unwind-{originalIdemKey}` for the reverse swap. A hedge
// is considered OPEN if there's a hedge-tagged tx with no matching
// unwind-tagged tx.
const HEDGE_KEY_PREFIX = 'hedge-'
const UNWIND_KEY_PREFIX = 'unwind-'

// Quick-pick hedge ratios as a fraction of available SRUB balance.
const HEDGE_RATIO_PRESETS = [
  { label: '25%', value: 0.25 },
  { label: '50%', value: 0.50 },
  { label: '75%', value: 0.75 },
  { label: '100%', value: 1.0 },
]

interface HedgeCandidate {
  pool: Pool
  baseToken: Token
  hedgeToken: Token
  baseIsX: boolean // true if SRUB is tokenX in the pool
}

/**
 * Hedge ratio → exposure amount. Pure function for unit testing —
 * keeps the UI free of "did I round?" guessing.
 */
export function hedgeAmountFromRatio(ratio: number, baseBalance: number): number {
  if (!Number.isFinite(ratio) || ratio <= 0) return 0
  if (!Number.isFinite(baseBalance) || baseBalance <= 0) return 0
  // Floor: never recommend hedging more than the user actually holds.
  return Math.floor(baseBalance * Math.min(ratio, 1))
}

/**
 * Mirror of swap rate display for hedges. Returns the effective FX rate
 * (foreign units per 1 base unit) as a Number for UI display.
 */
export function effectiveHedgeRate(amountIn: number, amountOut: number): number {
  if (!amountIn || amountIn <= 0) return 0
  return amountOut / amountIn
}

export default function HedgePage() {
  const queryClient = useQueryClient()

  const [selectedPoolId, setSelectedPoolId] = useState<string>('')
  const [amountSrub, setAmountSrub] = useState<number | null>(null)
  const [presetRatio, setPresetRatio] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState(false)

  const { data: tokenList } = useQuery({
    queryKey: ['tokens'],
    queryFn: () => tokens.getTokens(0, 200),
  })

  const { data: poolList } = useQuery({
    queryKey: ['pools', 'hedge'],
    queryFn: () => pools.getPools(0, 100),
  })

  const { data: myBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })

  // Resolve the base SRUB token (anchor of all hedge framing).
  const baseToken = useMemo<Token | null>(() => {
    return tokenList?.content?.find((t: Token) => t.symbol === BASE_SYMBOL) || null
  }, [tokenList])

  // Available SRUB balance — drives the "you have X rubles to hedge" stat
  // and the percentage presets.
  const srubBalance = useMemo<TokenBalance | null>(() => {
    if (!baseToken) return null
    return (myBalances || []).find((b: TokenBalance) => b.tokenId === baseToken.id) || null
  }, [myBalances, baseToken])

  // Hedge candidates — intersection of (FIAT_BACKED ≠ SRUB) × (ACTIVE pool with SRUB).
  const candidates = useMemo<HedgeCandidate[]>(() => {
    if (!baseToken || !tokenList?.content || !poolList?.content) return []
    const fiatTokens = tokenList.content.filter(
      (t: Token) => t.tokenType === 'FIAT_BACKED' && t.id !== baseToken.id && t.active,
    )
    const result: HedgeCandidate[] = []
    for (const hedgeToken of fiatTokens) {
      const pool = poolList.content.find((p: Pool) =>
        p.status === 'ACTIVE' &&
        ((p.tokenXId === baseToken.id && p.tokenYId === hedgeToken.id) ||
          (p.tokenYId === baseToken.id && p.tokenXId === hedgeToken.id)),
      )
      if (pool) {
        result.push({
          pool,
          baseToken,
          hedgeToken,
          baseIsX: pool.tokenXId === baseToken.id,
        })
      }
    }
    return result
  }, [baseToken, tokenList, poolList])

  const selectedCandidate = useMemo<HedgeCandidate | null>(() => {
    return candidates.find((c) => c.pool.id === selectedPoolId) || null
  }, [candidates, selectedPoolId])

  // Live quote against the same /swap/quote endpoint as SwapPage.
  const { data: quote, isLoading: quoteLoading } = useQuery({
    queryKey: ['hedgeQuote', selectedPoolId, amountSrub],
    queryFn: () => pools.getSwapQuote({
      poolId: selectedCandidate!.pool.id,
      tokenInId: selectedCandidate!.baseToken.id,
      amountIn: amountSrub!,
    }),
    enabled: !!selectedCandidate && !!amountSrub && amountSrub > 0,
    retry: false,
  })

  // Tight slippage default for hedges — treasurers care about predictability
  // more than swap speed, but we leave 0.3% so a small bin price drift
  // doesn't reject the order. (Discussion with PO: 0.5% on swap, 0.3% on
  // hedge — hedges are size-limited so impact is lower anyway.)
  const HEDGE_SLIPPAGE = 0.003
  const minAmountOut = quote ? Math.floor(quote.amountOut * (1 - HEDGE_SLIPPAGE)) : 0

  const executeMutation = useMutation({
    mutationFn: () => pools.executeSwap({
      poolId: selectedCandidate!.pool.id,
      tokenInId: selectedCandidate!.baseToken.id,
      amountIn: amountSrub!,
      minAmountOut,
      // Sprint 5 #5.14 — hedge-prefix idempotency key so "My open
      // hedges" table can find this swap later without a backend schema
      // change. The unwind flow reuses the same uuid suffix.
      idempotencyKey: HEDGE_KEY_PREFIX + crypto.randomUUID(),
    }),
    onSuccess: () => {
      setSuccess(true)
      setError(null)
      setAmountSrub(null)
      setPresetRatio(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myTransactions'] })
      setTimeout(() => setSuccess(false), 5000)
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } }
      setError(e?.response?.data?.message || 'Не удалось выполнить хедж')
    },
  })

  const handleRatioPreset = (ratio: number) => {
    if (!srubBalance) return
    const amount = hedgeAmountFromRatio(ratio, srubBalance.available)
    setAmountSrub(amount)
    setPresetRatio(ratio)
  }

  const handleAmountChange = (v: number | null) => {
    setAmountSrub(v)
    setPresetRatio(null) // manual edit clears preset highlight
  }

  // ── Sprint 5 #5.14 — open-hedge discovery + unwind flow ──

  /**
   * Latest 100 swap txs for the current user. We over-fetch (default
   * 20 too narrow if user is active) but bound the page size so the
   * grid doesn't blow up.
   */
  const { data: myTxs } = useQuery({
    queryKey: ['myTransactions', 'hedges'],
    queryFn: () => transactions.getMyTransactions(0, 100, { txType: 'SWAP' }),
  })

  /**
   * Open hedges = hedge-prefixed swaps whose idempotency-key suffix
   * doesn't appear in any unwind-prefixed tx. Display sorted by
   * created_at desc.
   */
  const openHedges = useMemo(() => {
    if (!myTxs?.content) return []
    const allSwaps: Transaction[] = myTxs.content
    const unwoundSuffixes = new Set<string>()
    for (const tx of allSwaps) {
      if (tx.idempotencyKey?.startsWith(UNWIND_KEY_PREFIX)) {
        unwoundSuffixes.add(tx.idempotencyKey.substring(UNWIND_KEY_PREFIX.length))
      }
    }
    return allSwaps.filter((tx) => {
      if (!tx.idempotencyKey?.startsWith(HEDGE_KEY_PREFIX)) return false
      if (tx.status !== 'CONFIRMED') return false
      return !unwoundSuffixes.has(tx.idempotencyKey)
    })
  }, [myTxs])

  /**
   * Unwind a hedge: swap target FX → SRUB at current rate. Reuses the
   * pool found from the original hedge's poolId. Idempotency-key tags
   * the unwind as paired with the original.
   */
  const unwindMutation = useMutation({
    mutationFn: async (hedge: Transaction) => {
      if (!hedge.poolId || !hedge.tokenOutId || !hedge.amountOut) {
        throw new Error('Хедж-транзакция не содержит данных для разворота')
      }
      // We swap back whatever amount we received. minAmountOut omitted
      // for simplicity; caller accepts current market rate (treasurer
      // who clicks "Unwind now" cares about closing > about price).
      await pools.executeSwap({
        poolId: hedge.poolId,
        tokenInId: hedge.tokenOutId,
        amountIn: hedge.amountOut,
        minAmountOut: 0,
        idempotencyKey: UNWIND_KEY_PREFIX + (hedge.idempotencyKey ?? hedge.id),
      })
    },
    onSuccess: () => {
      setSuccess(true)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myTransactions'] })
      queryClient.invalidateQueries({ queryKey: ['myTransactions', 'hedges'] })
      setTimeout(() => setSuccess(false), 5000)
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } }
      setError(e?.response?.data?.message || 'Не удалось закрыть хедж')
    },
  })

  const confirmUnwind = (hedge: Transaction) => {
    // Sprint 7 #UX-016 — softer language per UX-REVIEW spec.
    // Was: "Минимальный объём не задан — курс на момент исполнения."
    // That phrasing alarmed treasurers (sounded like no protection at all).
    // New copy: matter-of-fact expected-receive + neutral note about
    // execution-time rate. Same operation, calmer reading.
    const expectedRubBack = hedge.amountIn?.toLocaleString('ru-RU') ?? '?'
    Modal.confirm({
      title: 'Закрыть хедж?',
      content: (
        <Space direction="vertical">
          <Text>
            Будет выполнен обратный своп {hedge.amountOut?.toLocaleString('ru-RU')} единиц
            обратно в SRUB.
          </Text>
          <Text>
            Ориентировочно получите <b>~{expectedRubBack} SRUB</b>;
            фактическая сумма зависит от курса на момент исполнения.
          </Text>
        </Space>
      ),
      okText: 'Закрыть хедж',
      cancelText: 'Отмена',
      onOk: () => unwindMutation.mutate(hedge),
    })
  }

  /**
   * Sprint 6 #6.14 — «Закрыть всё»: mass-action для treasurer'а который
   * хочет flat the book на конец дня. Sequentially fires unwind for
   * every open hedge — серийно, не параллельно, чтобы pool не получил
   * concurrent swaps на тот же бин (хотя optimistic lock #4.7 справится,
   * последовательность даёт более предсказуемые price impacts).
   */
  const confirmUnwindAll = () => {
    if (openHedges.length === 0) return
    const totalHedged = openHedges.reduce((sum, h) => sum + (h.amountIn ?? 0), 0)
    Modal.confirm({
      title: `Закрыть все хеджи (${openHedges.length})?`,
      width: 480,
      content: (
        <Space direction="vertical" size={8}>
          <Text>
            Будут последовательно закрыты <b>{openHedges.length}</b> открытых хеджа
            на общую сумму <b>{totalHedged.toLocaleString('ru-RU')} SRUB</b>.
          </Text>
          <Text type="warning" style={{ fontSize: 12 }}>
            Операция необратима. Каждое закрытие выполняется как обычный
            своп по текущему курсу.
          </Text>
          <Text type="secondary" style={{ fontSize: 11 }}>
            Хеджи закрываются последовательно — это даёт более предсказуемое
            влияние на цены пулов, чем параллельное исполнение.
          </Text>
        </Space>
      ),
      okText: `Закрыть все ${openHedges.length}`,
      okButtonProps: { danger: true },
      cancelText: 'Отмена',
      onOk: async () => {
        for (const hedge of openHedges) {
          try {
            await unwindMutation.mutateAsync(hedge)
          } catch (e) {
            // Stop on first error so caller can investigate; success
            // hedges already closed stay closed (idempotent unwind key).
            return
          }
        }
      },
    })
  }

  // Token-symbol lookup for the open-hedges table.
  const tokenMap = useMemo(() => {
    const m = new Map<string, Token>()
    if (tokenList?.content) {
      for (const t of tokenList.content) m.set(t.id, t)
    }
    return m
  }, [tokenList])

  return (
    <div style={{ maxWidth: 1080, margin: '0 auto' }}>
      <div style={{ marginBottom: 24 }}>
        <Space size={12} align="center">
          <SafetyCertificateOutlined style={{ fontSize: 22, color: 'var(--sber-green)' }} />
          <Title level={4} className="sber-page-title" style={{ margin: 0 }}>
            Хеджирование валютного риска
          </Title>
        </Space>
        <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
          Зафиксируйте курс рубля к иностранной валюте через DLMM-пулы — без выхода в SWIFT.
          Хедж работает как мгновенный своп: SRUB конвертируется в выбранную валюту,
          вы получаете токенизированную позицию, которую можно держать или развернуть обратно.
        </Paragraph>
      </div>

      {/* Exposure summary — anchors the whole UX in "how many rubles do I have?" */}
      <Card className="sber-card" style={{ marginBottom: 20 }}>
        <Row align="middle" gutter={24}>
          <Col xs={24} sm={12}>
            <Statistic
              title={
                <Space>
                  <Text type="secondary" style={{ fontSize: 13 }}>Подверженность валютному риску</Text>
                  <Tooltip title="Сумма доступных рублей, которые можно конвертировать в иностранную валюту в качестве хеджа.">
                    <InfoCircleOutlined style={{ color: 'var(--text-muted)' }} />
                  </Tooltip>
                </Space>
              }
              value={srubBalance?.available ?? 0}
              suffix="SRUB"
              valueStyle={{ color: 'var(--sber-green-deep)', fontSize: 28, fontWeight: 700 }}
              groupSeparator=" "
            />
          </Col>
          <Col xs={24} sm={12} style={{ textAlign: 'right' }}>
            <Text type="secondary" style={{ fontSize: 12 }}>В заморозке</Text>
            <div style={{ fontSize: 16, fontWeight: 600, color: 'var(--text-primary)' }}>
              {(srubBalance?.locked ?? 0).toLocaleString('ru-RU')} SRUB
            </div>
          </Col>
        </Row>
      </Card>

      {success && (
        <Alert message="Хедж выполнен — токены зачислены на счёт" type="success" showIcon
          closable onClose={() => setSuccess(false)}
          style={{ marginBottom: 16, borderRadius: 12 }} />
      )}
      {error && (
        <Alert message={error} type="error" showIcon closable
          onClose={() => setError(null)}
          style={{ marginBottom: 16, borderRadius: 12 }} />
      )}

      <Row gutter={20}>
        {/* Hedge candidate cards on the left — tap one to load it into the builder. */}
        <Col xs={24} lg={12}>
          <Card className="sber-card" title="Доступные пары хеджирования" style={{ marginBottom: 20 }}>
            {candidates.length === 0 ? (
              <Text type="secondary">
                Нет доступных FX-пар. Проверьте, что в каталоге есть активные пулы SRUB ↔ FIAT_BACKED.
              </Text>
            ) : (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                {candidates.map((c) => {
                  const selected = c.pool.id === selectedPoolId
                  return (
                    <div
                      key={c.pool.id}
                      onClick={() => setSelectedPoolId(c.pool.id)}
                      style={{
                        cursor: 'pointer',
                        padding: 14,
                        borderRadius: 12,
                        border: `1px solid ${selected ? 'var(--sber-green)' : 'var(--border-light)'}`,
                        background: selected ? 'var(--sber-green-light)' : '#FFFFFF',
                        transition: 'all 0.15s ease',
                      }}
                    >
                      <Row justify="space-between" align="middle">
                        <Col>
                          <Space size={10}>
                            <Tag color="green" style={{ fontWeight: 600, fontSize: 13 }}>
                              {c.baseToken.symbol} → {c.hedgeToken.symbol}
                            </Tag>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              {c.hedgeToken.name}
                            </Text>
                          </Space>
                          <div style={{ marginTop: 6 }}>
                            <Text type="secondary" style={{ fontSize: 11 }}>
                              Текущая котировка
                            </Text>
                            <div style={{ fontSize: 15, fontWeight: 600 }}>
                              1 {c.baseToken.symbol} ≈ {c.pool.currentPrice.toFixed(6)} {c.hedgeToken.symbol}
                            </div>
                          </div>
                        </Col>
                        <Col>
                          <Tag style={{ borderRadius: 999, fontSize: 11 }}>
                            комиссия {bpsToPercent(c.pool.baseFeeBps)}
                          </Tag>
                        </Col>
                      </Row>
                    </div>
                  )
                })}
              </Space>
            )}
          </Card>
        </Col>

        {/* Hedge builder — the actual interaction surface. */}
        <Col xs={24} lg={12}>
          <Card
            className="sber-card"
            title="Калькулятор хеджа"
            extra={
              selectedCandidate && (
                <Tag color="green" style={{ borderRadius: 999 }}>
                  <ThunderboltFilled style={{ fontSize: 10, marginRight: 4 }} />
                  активный пул
                </Tag>
              )
            }
          >
            {!selectedCandidate && (
              <Alert
                type="info"
                showIcon
                message="Выберите пару слева, чтобы рассчитать хедж"
                style={{ borderRadius: 12 }}
              />
            )}

            {selectedCandidate && (
              <Space direction="vertical" size={18} style={{ width: '100%' }}>
                <div>
                  <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
                    Какую долю SRUB-позиции хеджируем?
                  </Text>
                  <Space wrap size={6}>
                    {HEDGE_RATIO_PRESETS.map((p) => (
                      <Button
                        key={p.value}
                        type={presetRatio === p.value ? 'primary' : 'default'}
                        size="middle"
                        onClick={() => handleRatioPreset(p.value)}
                        disabled={!srubBalance || srubBalance.available <= 0}
                      >
                        {p.label}
                      </Button>
                    ))}
                  </Space>
                </div>

                <div>
                  <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
                    Или укажите сумму вручную (SRUB)
                  </Text>
                  <InputNumber
                    style={{ width: '100%' }}
                    size="large"
                    placeholder="0"
                    value={amountSrub}
                    onChange={handleAmountChange}
                    min={0}
                    max={srubBalance?.available ?? undefined}
                    controls={false}
                    formatter={(v) => v ? Number(v).toLocaleString('ru-RU') : ''}
                    parser={(v) => Number((v || '').toString().replace(/\s/g, '')) as 0}
                  />
                </div>

                {quoteLoading && (
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text-muted)' }}>
                    <Spin size="small" /> <Text type="secondary">Расчёт котировки…</Text>
                  </div>
                )}

                {quote && !quoteLoading && (
                  <div
                    style={{
                      background: 'var(--grad-brand-soft)',
                      borderRadius: 12,
                      padding: 16,
                    }}
                  >
                    <Row gutter={[8, 8]}>
                      <Col span={12}>
                        <Text type="secondary" style={{ fontSize: 11 }}>Получите</Text>
                        <div style={{ fontSize: 22, fontWeight: 700, color: 'var(--sber-green-deep)' }}>
                          {quote.amountOut.toLocaleString('ru-RU')} {selectedCandidate.hedgeToken.symbol}
                        </div>
                      </Col>
                      <Col span={12} style={{ textAlign: 'right' }}>
                        <Text type="secondary" style={{ fontSize: 11 }}>Эффективный курс</Text>
                        <div style={{ fontSize: 14, fontWeight: 600 }}>
                          1 {selectedCandidate.baseToken.symbol} ≈{' '}
                          {effectiveHedgeRate(quote.amountIn, quote.amountOut).toFixed(6)}
                        </div>
                      </Col>
                      <Col span={12}>
                        <Text type="secondary" style={{ fontSize: 11 }}>Комиссия пула</Text>
                        <div style={{ fontSize: 13 }}>
                          {quote.fee.toLocaleString('ru-RU')} {selectedCandidate.baseToken.symbol}
                        </div>
                      </Col>
                      <Col span={12} style={{ textAlign: 'right' }}>
                        <Text type="secondary" style={{ fontSize: 11 }}>Влияние на цену</Text>
                        <div style={{
                          fontSize: 13,
                          color: quote.priceImpact < 0.5 ? 'var(--sber-green)'
                            : quote.priceImpact < 2 ? 'var(--sber-amber)' : '#EF4444',
                        }}>
                          {quote.priceImpact > 0
                            ? <RiseOutlined style={{ marginRight: 4 }} />
                            : <FallOutlined style={{ marginRight: 4 }} />}
                          {quote.priceImpact.toFixed(2)}%
                        </div>
                      </Col>
                      <Col span={24}>
                        <Text type="secondary" style={{ fontSize: 11 }}>
                          Мин. к получению (slippage 0.3%): {minAmountOut.toLocaleString('ru-RU')}{' '}
                          {selectedCandidate.hedgeToken.symbol}
                        </Text>
                      </Col>
                    </Row>
                  </div>
                )}

                <Button
                  type="primary"
                  size="large"
                  block
                  className="sber-swap-cta"
                  disabled={!quote || executeMutation.isPending}
                  loading={executeMutation.isPending}
                  onClick={() => executeMutation.mutate()}
                >
                  {!amountSrub
                    ? 'Введите сумму или выберите долю'
                    : !quote
                    ? 'Ждём котировку…'
                    : executeMutation.isPending
                    ? 'Выполнение хеджа…'
                    : `Захеджировать ${amountSrub.toLocaleString('ru-RU')} SRUB`}
                </Button>

                <Text type="secondary" style={{ fontSize: 11, textAlign: 'center', display: 'block' }}>
                  История хеджей ниже + полная — на странице «Транзакции».
                </Text>
              </Space>
            )}
          </Card>
        </Col>
      </Row>

      {/* Sprint 5 #5.14 — Open hedges table. Each row gets an "Закрыть" */}
      {/* action that fires a reverse swap (target FX → SRUB) tagged with */}
      {/* a paired idempotency key so the hedge falls off this list. */}
      {/* Sprint 6 #6.14 — added "Закрыть всё" mass-action button in card extra. */}
      <Card
        className="sber-card"
        title={
          <Space>
            <Text strong>Открытые хеджи</Text>
            <Tag color="green">{openHedges.length}</Tag>
          </Space>
        }
        extra={
          openHedges.length > 0 && (
            <Button
              danger
              size="small"
              icon={<RollbackOutlined />}
              onClick={confirmUnwindAll}
              loading={unwindMutation.isPending}
            >
              Закрыть всё
            </Button>
          )
        }
        style={{ marginTop: 20 }}
      >
        {openHedges.length === 0 ? (
          <Text type="secondary">
            Нет открытых хеджей. Создайте хедж в калькуляторе выше — он появится здесь
            с кнопкой быстрого закрытия.
          </Text>
        ) : (
          <Table
            dataSource={openHedges}
            rowKey="id"
            pagination={false}
            size="middle"
            className="sber-table"
            columns={[
              {
                title: 'Дата',
                dataIndex: 'createdAt',
                render: (v: string) => new Date(v).toLocaleDateString('ru-RU'),
                width: 110,
              },
              {
                title: 'Пара',
                render: (_, tx: Transaction) => {
                  const inSym = tx.tokenInId ? tokenMap.get(tx.tokenInId)?.symbol : '?'
                  const outSym = tx.tokenOutId ? tokenMap.get(tx.tokenOutId)?.symbol : '?'
                  return (
                    <Tag color="green" style={{ fontWeight: 600 }}>
                      {inSym} → {outSym}
                    </Tag>
                  )
                },
                width: 140,
              },
              {
                title: 'Хеджировано (SRUB)',
                dataIndex: 'amountIn',
                render: (v: number | null) => v?.toLocaleString('ru-RU') ?? '—',
                align: 'right',
              },
              {
                title: 'Получено',
                render: (_, tx: Transaction) => {
                  const outSym = tx.tokenOutId ? tokenMap.get(tx.tokenOutId)?.symbol : ''
                  return `${tx.amountOut?.toLocaleString('ru-RU') ?? '—'} ${outSym}`
                },
                align: 'right',
              },
              {
                title: '',
                width: 130,
                render: (_, tx: Transaction) => (
                  <Button
                    type="default"
                    size="small"
                    icon={<RollbackOutlined />}
                    danger
                    onClick={() => confirmUnwind(tx)}
                    loading={unwindMutation.isPending}
                  >
                    Закрыть
                  </Button>
                ),
              },
            ]}
          />
        )}
      </Card>
    </div>
  )
}
