import { useMemo, useState } from 'react'
import {
  Card,
  Typography,
  Space,
  Table,
  InputNumber,
  Button,
  Alert,
  Tag,
  Progress,
  Steps,
  Tooltip,
  Empty,
} from 'antd'
import {
  ThunderboltFilled,
  CheckCircleFilled,
  WarningFilled,
  RetweetOutlined,
  InfoCircleOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { tokens, pools as poolsApi, balances } from '@/api/services'
import type { Token, Pool, TokenBalance } from '@/api/types'
import { formatRub } from '@/components/StatCard'
import {
  planRebalance,
  validateTargets,
  type PortfolioTokenView,
  type TargetAllocation,
  type RebalancePlan,
} from '@/lib/rebalancePlanner'
import { uuid } from '../lib/uuid'

const { Title, Text } = Typography

/**
 * Sprint 10 F-07 — Portfolio rebalancer wizard.
 *
 * Three-step flow:
 *   1. View — current portfolio with % allocation.
 *   2. Plan — user sets target % per token; planner computes hops.
 *   3. Execute — runs the swap chain sequentially, stop-on-first-failure.
 *
 * Why a wizard not a single page: each step has a distinct cognitive
 * load (read → decide → confirm). A linear form on one page mixes the
 * three and the user has trouble seeing whether they're past the
 * point of no return. Steps component gives them a back-out.
 *
 * Cost / fee disclosure: every hop carries the existing per-swap fee
 * (0.3% default for SRUB pairs). The wizard surfaces the total
 * estimated fee in the Plan step BEFORE the user confirms execution
 * so there's no surprise on the receipt.
 */
export default function RebalancePage() {
  const queryClient = useQueryClient()
  const [step, setStep] = useState<0 | 1 | 2>(0)
  const [targets, setTargets] = useState<Record<string, number>>({})
  const [validationError, setValidationError] = useState<string | null>(null)
  const [executionLog, setExecutionLog] = useState<Array<{ hop: number; status: 'ok' | 'fail'; msg: string }>>([])
  const [isExecuting, setIsExecuting] = useState(false)

  const { data: myBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })
  const { data: tokenList } = useQuery({
    queryKey: ['tokens'],
    queryFn: () => tokens.getTokens(0, 100),
  })
  const { data: poolList } = useQuery({
    queryKey: ['pools'],
    queryFn: () => poolsApi.getPools(0, 100),
  })

  // SRUB-anchored price map — same trick as DashboardPage / ProfilePage.
  // Symbol → ₽-equivalent unit price.
  const rubPriceBySymbol = useMemo(() => {
    const m = new Map<string, number>()
    m.set('SRUB', 1)
    for (const pool of poolList?.content ?? []) {
      if (pool.tokenYSymbol === 'SRUB') m.set(pool.tokenXSymbol, pool.currentPrice)
      else if (pool.tokenXSymbol === 'SRUB' && pool.currentPrice > 0) {
        m.set(pool.tokenYSymbol, 1 / pool.currentPrice)
      }
    }
    return m
  }, [poolList])

  const tokenBySymbol = useMemo(() => {
    const m = new Map<string, Token>()
    for (const t of tokenList?.content ?? []) m.set(t.symbol, t)
    return m
  }, [tokenList])

  // Portfolio view — only tokens with non-zero balance are eligible
  // for rebalance display. Tokens that user wants to BUY but doesn't
  // hold yet need to be added via the "Добавить токен" affordance
  // (Sprint 11 — for now the planner skips them with a friendly note).
  const portfolio: PortfolioTokenView[] = useMemo(() => {
    return (myBalances ?? [])
      .filter((b: TokenBalance) => (b.available + b.locked) > 0)
      .map((b: TokenBalance) => ({
        tokenId: b.tokenId,
        symbol: b.symbol,
        amount: b.available + b.locked,
        priceRub: rubPriceBySymbol.get(b.symbol) ?? 0,
      }))
  }, [myBalances, rubPriceBySymbol])

  const totalRub = portfolio.reduce((s, p) => s + p.amount * p.priceRub, 0)

  // Initialise the targets map to "current allocation" on first render
  // — gives the user a sensible baseline to nudge from.
  useMemo(() => {
    if (Object.keys(targets).length === 0 && portfolio.length > 0 && totalRub > 0) {
      const seed: Record<string, number> = {}
      for (const p of portfolio) {
        seed[p.symbol] = +((p.amount * p.priceRub / totalRub) * 100).toFixed(1)
      }
      setTargets(seed)
    }
  }, [portfolio, totalRub, targets])

  const targetList: TargetAllocation[] = Object.entries(targets).map(([symbol, targetPct]) => ({
    symbol,
    targetPct,
  }))

  const plan: RebalancePlan = useMemo(() => planRebalance(portfolio, targetList), [portfolio, targetList])

  // Estimate total fee: 30 bps per hop is the default base fee; rough
  // but conservative. Real per-pool fee shows on the swap receipt.
  const estimatedTotalFeeRub = plan.hops.length * 0 + plan.totalRubMoved * 0.003

  const swapMutation = useMutation({
    mutationFn: async (req: {
      poolId: string
      tokenInId: string
      amountIn: number
      minAmountOut: number
    }) => {
      return poolsApi.executeSwap({
        ...req,
        idempotencyKey: uuid(),
      })
    },
  })

  const executePlan = async (): Promise<void> => {
    setIsExecuting(true)
    setExecutionLog([])

    for (let i = 0; i < plan.hops.length; i++) {
      const hop = plan.hops[i]
      const fromToken = tokenBySymbol.get(hop.fromSymbol)
      const toToken = tokenBySymbol.get(hop.toSymbol)
      if (!fromToken || !toToken) {
        setExecutionLog((prev) => [...prev, {
          hop: i,
          status: 'fail',
          msg: `Не найден токен ${hop.fromSymbol} или ${hop.toSymbol}`,
        }])
        break
      }

      // Find the pool routing fromToken ↔ toToken (one of them is SRUB).
      const pool = (poolList?.content ?? []).find((p: Pool) =>
        (p.tokenXId === fromToken.id && p.tokenYId === toToken.id) ||
        (p.tokenXId === toToken.id && p.tokenYId === fromToken.id),
      )
      if (!pool) {
        setExecutionLog((prev) => [...prev, {
          hop: i,
          status: 'fail',
          msg: `Нет пула ${hop.fromSymbol}/${hop.toSymbol}`,
        }])
        break
      }

      try {
        // Get a quote to compute minAmountOut at default 0.5% slippage.
        const quote = await poolsApi.getSwapQuote({
          poolId: pool.id,
          tokenInId: fromToken.id,
          amountIn: hop.amountIn,
        })
        const minOut = Math.floor(quote.amountOut * 0.995)
        await swapMutation.mutateAsync({
          poolId: pool.id,
          tokenInId: fromToken.id,
          amountIn: hop.amountIn,
          minAmountOut: minOut,
        })
        setExecutionLog((prev) => [...prev, {
          hop: i,
          status: 'ok',
          msg: `${hop.fromSymbol} → ${hop.toSymbol}: ${hop.amountIn.toLocaleString('ru-RU')} (≈ ${formatRub(hop.approxRubValue)})`,
        }])
      } catch (err: unknown) {
        const e = err as { response?: { data?: { message?: string } } }
        setExecutionLog((prev) => [...prev, {
          hop: i,
          status: 'fail',
          msg: `Шаг ${i + 1} (${hop.fromSymbol} → ${hop.toSymbol}): ${e?.response?.data?.message ?? 'ошибка обмена'}`,
        }])
        break
      }
    }

    setIsExecuting(false)
    queryClient.invalidateQueries({ queryKey: ['myBalances'] })
    queryClient.invalidateQueries({ queryKey: ['myTransactions'] })
  }

  // STEP 0 — VIEW current portfolio.
  const renderViewStep = () => {
    if (portfolio.length === 0) {
      return (
        <Empty
          description="В портфеле нет активов для ребаланса"
          style={{ padding: 40 }}
        />
      )
    }
    const portfolioCols = [
      { title: 'Токен', dataIndex: 'symbol', key: 'symbol', render: (s: string) => <Tag color="green" style={{ borderRadius: 'var(--radius-pill)', fontWeight: 600 }}>{s}</Tag> },
      { title: 'Кол-во', dataIndex: 'amount', key: 'amount', align: 'right' as const, render: (a: number) => a.toLocaleString('ru-RU') },
      { title: 'Стоимость, ₽', key: 'value', align: 'right' as const, render: (_: unknown, r: PortfolioTokenView) => formatRub(r.amount * r.priceRub) },
      {
        title: 'Доля',
        key: 'pct',
        align: 'right' as const,
        render: (_: unknown, r: PortfolioTokenView) => {
          const pct = (r.amount * r.priceRub / totalRub) * 100
          return (
            <Space direction="vertical" size={2} style={{ width: '100%', minWidth: 120 }}>
              <Text strong style={{ fontVariantNumeric: 'tabular-nums' }}>{pct.toFixed(1)}%</Text>
              <Progress percent={pct} size="small" showInfo={false} strokeColor="var(--sber-green)" />
            </Space>
          )
        },
      },
    ]
    return (
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Alert
          message="Текущее распределение"
          description={`Общая стоимость портфеля: ${formatRub(totalRub)}. Перейдите к шагу «Цель», чтобы задать желаемые доли.`}
          type="info"
          showIcon
        />
        <Table
          columns={portfolioCols}
          dataSource={portfolio}
          rowKey="symbol"
          pagination={false}
          size="middle"
        />
        <Button type="primary" size="large" onClick={() => setStep(1)}>
          Задать цель →
        </Button>
      </Space>
    )
  }

  // STEP 1 — set TARGETS.
  const renderPlanStep = () => {
    const sum = targetList.reduce((s, t) => s + t.targetPct, 0)
    const err = validateTargets(targetList)
    return (
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Alert
          message="Задайте целевые доли"
          description={`Сумма всех долей должна быть 100%. Сейчас: ${sum.toFixed(1)}%. Допуск: ±0.5% — ребаланс не будет переключать токены в пределах допуска.`}
          type={err ? 'warning' : 'info'}
          showIcon
        />
        <Table
          columns={[
            { title: 'Токен', dataIndex: 'symbol', key: 'symbol', render: (s: string) => <Tag color="green" style={{ borderRadius: 'var(--radius-pill)' }}>{s}</Tag> },
            {
              title: 'Текущая доля',
              key: 'current',
              align: 'right' as const,
              render: (_: unknown, r: PortfolioTokenView) => `${((r.amount * r.priceRub / totalRub) * 100).toFixed(1)}%`,
            },
            {
              title: <>Целевая доля, % <Tooltip title="Положительное число от 0 до 100"><InfoCircleOutlined /></Tooltip></>,
              key: 'target',
              align: 'right' as const,
              render: (_: unknown, r: PortfolioTokenView) => (
                <InputNumber
                  min={0}
                  max={100}
                  step={1}
                  value={targets[r.symbol] ?? 0}
                  onChange={(v) => {
                    setTargets({ ...targets, [r.symbol]: Number(v) || 0 })
                    setValidationError(null)
                  }}
                  style={{ width: 100 }}
                />
              ),
            },
          ]}
          dataSource={portfolio}
          rowKey="symbol"
          pagination={false}
          size="middle"
        />
        {validationError && <Alert type="error" message={validationError} showIcon />}
        <Space>
          <Button onClick={() => setStep(0)}>← Назад</Button>
          <Button
            type="primary"
            disabled={!!err}
            onClick={() => {
              if (err) { setValidationError(err); return }
              setStep(2)
            }}
          >
            Показать план →
          </Button>
        </Space>
      </Space>
    )
  }

  // STEP 2 — EXECUTE plan.
  const renderExecuteStep = () => {
    const hopsCols = [
      { title: '#', key: 'idx', render: (_: unknown, __: unknown, i: number) => i + 1, width: 40 },
      { title: 'Из', dataIndex: 'fromSymbol', key: 'from', render: (s: string) => <Tag>{s}</Tag> },
      { title: 'В', dataIndex: 'toSymbol', key: 'to', render: (s: string) => <Tag color="green">{s}</Tag> },
      { title: 'Кол-во', dataIndex: 'amountIn', key: 'amount', align: 'right' as const, render: (a: number) => a.toLocaleString('ru-RU') },
      { title: '≈ ₽', dataIndex: 'approxRubValue', key: 'rub', align: 'right' as const, render: (r: number) => formatRub(r) },
      { title: 'Причина', dataIndex: 'reason', key: 'reason', render: (r: string) => <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{r}</Text> },
    ]
    return (
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        {plan.hops.length === 0 ? (
          <Alert
            message="Все доли уже в пределах допуска — ребаланс не нужен"
            type="success"
            showIcon
            icon={<CheckCircleFilled />}
          />
        ) : (
          <>
            <Alert
              message={`План ребаланса: ${plan.hops.length} обмен(а/ов)`}
              description={
                <Space direction="vertical" size={4}>
                  <Text>Будет перенесено: <Text strong>{formatRub(plan.totalRubMoved)}</Text></Text>
                  <Text>Оценочная комиссия (≈ 30 bps): <Text strong>{formatRub(estimatedTotalFeeRub)}</Text></Text>
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                    Маршрут через SRUB как pivot-валюту. При сбое одного шага выполнение останавливается — балансы откатывать не нужно.
                  </Text>
                </Space>
              }
              type="warning"
              showIcon
              icon={<WarningFilled />}
            />
            <Table
              columns={hopsCols}
              dataSource={plan.hops}
              rowKey={(_, i) => String(i)}
              pagination={false}
              size="middle"
            />
          </>
        )}
        {plan.skipped.length > 0 && (
          <Alert
            type="info"
            showIcon
            message="Пропущено"
            description={
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                {plan.skipped.map((s, i) => (
                  <Text key={i} type="secondary" style={{ fontSize: 'var(--text-xs)' }}>· {s.symbol}: {s.reason}</Text>
                ))}
              </Space>
            }
          />
        )}
        {executionLog.length > 0 && (
          <Card title={<Space><RetweetOutlined /><Text strong>Журнал выполнения</Text></Space>} size="small">
            <Space direction="vertical" size={6} style={{ width: '100%' }}>
              {executionLog.map((l, i) => (
                <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  {l.status === 'ok'
                    ? <CheckCircleFilled style={{ color: 'var(--sber-green)' }} />
                    : <WarningFilled style={{ color: 'var(--plasma-critical)' }} />}
                  <Text type={l.status === 'fail' ? 'danger' : undefined} style={{ fontSize: 'var(--text-sm)' }}>{l.msg}</Text>
                </div>
              ))}
            </Space>
          </Card>
        )}
        <Space>
          <Button onClick={() => setStep(1)} disabled={isExecuting}>← Изменить цель</Button>
          {plan.hops.length > 0 && (
            <Button
              type="primary"
              icon={<ThunderboltFilled />}
              loading={isExecuting}
              disabled={executionLog.some((l) => l.status === 'fail')}
              onClick={executePlan}
            >
              Выполнить ребаланс
            </Button>
          )}
        </Space>
      </Space>
    )
  }

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <div>
        <Title level={4} className="sber-page-title" style={{ marginBottom: 4 }}>
          Ребаланс портфеля
        </Title>
        <Text type="secondary">
          Привести фактические доли активов к желаемой структуре одним кликом
        </Text>
      </div>

      <Card className="sber-card">
        <Steps
          current={step}
          items={[
            { title: 'Текущая структура' },
            { title: 'Цель' },
            { title: 'План + исполнение' },
          ]}
          style={{ marginBottom: 24 }}
        />
        {step === 0 && renderViewStep()}
        {step === 1 && renderPlanStep()}
        {step === 2 && renderExecuteStep()}
      </Card>
    </Space>
  )
}
