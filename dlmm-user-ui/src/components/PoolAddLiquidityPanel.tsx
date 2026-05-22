import { useEffect, useMemo, useState } from 'react'
import { Typography, Space, InputNumber, Button, Alert, Tag, Tooltip, Slider } from 'antd'
import { PlusOutlined, InfoCircleOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { pools, balances } from '@/api/services'
import type { Pool, LiquidityStrategy } from '@/api/types'
import { formatCompact, formatRub } from '@/lib/format'

const { Text } = Typography

/**
 * Sprint 9-DS-r4 — Meteora-style "Create Position" panel embedded in
 * the pool page (one of the right-rail tabs).
 *
 * <p>Lifts the add-liquidity form from /pools/:id/liquidity into the
 * pool page so an LP never leaves the pool to manage their position.
 * Mirror of Meteora's Dynamic Terminal: Amount → Strategy (Spot /
 * Curve / Bid Ask with SVG glyph icons) → Price Range → Add.
 */

interface PoolAddLiquidityPanelProps {
  pool: Pool
  /**
   * Sprint 9-DS-r4 (P1-2) — every change to strategy / bin range is
   * forwarded so the parent page (PoolDetailPage) can mirror the
   * pending distribution onto the bin chart as a live preview.
   * Null when nothing meaningful to preview (range invalid).
   */
  onPreviewChange?: (preview: {
    binMin: number
    binMax: number
    strategy: LiquidityStrategy
  } | null) => void
}

// Inline SVG glyphs matching Meteora's strategy icons.
const StrategyIcon = ({ kind }: { kind: LiquidityStrategy }) => {
  const bars = (heights: number[]) => (
    <svg width="40" height="18" viewBox="0 0 40 18" style={{ display: 'block' }}>
      {heights.map((h, i) => (
        <rect
          key={i}
          x={i * 5 + 1}
          y={18 - h}
          width="3"
          height={h}
          fill="currentColor"
          rx="0.5"
        />
      ))}
    </svg>
  )
  switch (kind) {
    case 'SPOT':
      // Flat / uniform: equal-height bars (real Meteora pattern).
      return bars([10, 10, 10, 10, 10, 10, 10])
    case 'CURVE':
      // Peak at middle (bell-ish): low on edges, tall in middle.
      return bars([4, 7, 11, 14, 11, 7, 4])
    case 'BID_ASK':
      // U-shape: tall on edges, low in middle.
      return bars([13, 9, 4, 2, 4, 9, 13])
  }
}

const STRATEGIES: { value: LiquidityStrategy; label: string }[] = [
  { value: 'SPOT', label: 'Spot' },
  { value: 'CURVE', label: 'Curve' },
  { value: 'BID_ASK', label: 'Bid Ask' },
]

export default function PoolAddLiquidityPanel({ pool, onPreviewChange }: PoolAddLiquidityPanelProps) {
  const queryClient = useQueryClient()
  const [strategy, setStrategy] = useState<LiquidityStrategy>('SPOT')
  // Default to ±10 bins around the active bin (matches our hint copy).
  const [binMin, setBinMin] = useState<number | null>(pool.activeBinId - 10)
  const [binMax, setBinMax] = useState<number | null>(pool.activeBinId + 10)

  // Sprint 9-DS-r4 (P1-2) — push preview state up on every change so
  // the bin chart redraws the user-bin overlay live. Cleared on
  // unmount so navigating away (or switching tab) leaves the chart
  // showing only the user's real positions.
  useEffect(() => {
    if (binMin != null && binMax != null && binMax >= binMin) {
      onPreviewChange?.({ binMin, binMax, strategy })
    } else {
      onPreviewChange?.(null)
    }
    return () => onPreviewChange?.(null)
  }, [binMin, binMax, strategy, onPreviewChange])
  const [amountX, setAmountX] = useState<number | null>(null)
  const [amountY, setAmountY] = useState<number | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const { data: myBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })
  const balanceX = myBalances?.find((b) => b.symbol === pool.tokenXSymbol)
  const balanceY = myBalances?.find((b) => b.symbol === pool.tokenYSymbol)

  const addMutation = useMutation({
    mutationFn: () =>
      pools.addLiquidity({
        poolId: pool.id,
        amountX: amountX!,
        amountY: amountY!,
        binRangeMin: binMin!,
        binRangeMax: binMax!,
        strategy,
        idempotencyKey: crypto.randomUUID(),
      }),
    onSuccess: () => {
      setSuccess('Ликвидность успешно добавлена')
      setError(null)
      setAmountX(null)
      setAmountY(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['poolDetail', pool.id] })
      setTimeout(() => setSuccess(null), 5000)
    },
    onError: (err: any) => {
      setError(err?.response?.data?.message || 'Ошибка при добавлении ликвидности')
    },
  })

  const totalBins =
    binMin != null && binMax != null && binMax >= binMin ? binMax - binMin + 1 : 0
  const canAdd =
    amountX != null && amountY != null && amountX > 0 && amountY > 0 &&
    binMin != null && binMax != null && binMax > binMin && totalBins <= 1000

  /**
   * Sprint 9-DS-r4 (P1-7) — Meteora-style "Cost required to create 1
   * position" estimate.
   *
   * <p>Components:
   *   - capitalRub: total committed value in ₽ (rough — for the
   *     SRUB-quoted pools it's exact; for crypto-crypto pools it's a
   *     local-price-only estimate without an FX leg).
   *   - bookkeepingRub: 5 ₽ per bin, placeholder until we wire real
   *     storage-fee accounting (per backlog "Static for now, exact
   *     later" — Sprint 10 #DEX-P0 will replace with the actual
   *     per-bin gas-equivalent once a 1С audit lands).
   *   - protocolFeeRub: zero for adds today (protocol_fee is taken on
   *     SWAPS, not on LP add — see SwapService.execute). Surface a
   *     "0 ₽" line anyway so the table is consistent with Meteora's.
   */
  const COST_PER_BIN_RUB = 5
  const capitalRub = useMemo(() => {
    if (amountX == null && amountY == null) return 0
    const x = amountX ?? 0
    const y = amountY ?? 0
    const xInY = (pool.currentPrice ?? 0) * x
    // Pool is quoted in tokenY; if tokenY isn't SRUB we still display
    // the number using ₽ formatting — it's a "value in pair-quote"
    // estimate, the unit caveat lives in the tooltip below.
    return xInY + y
  }, [amountX, amountY, pool.currentPrice])
  const bookkeepingRub = totalBins * COST_PER_BIN_RUB
  const protocolFeeRub = 0
  const totalCostRub = bookkeepingRub + protocolFeeRub

  /**
   * Sprint 12 G-22 — price impact estimate for the act of adding LP.
   *
   * Premise: at the active bin, the canonical X:Y ratio is determined
   * by `pool.currentPrice`. If your deposit X+Y matches that ratio,
   * the active bin's reserves grow proportionally — no price move,
   * impact ≈ 0. If your deposit is skewed (mostly Y, or mostly X),
   * the imbalance gets absorbed via implicit "internal swap" which
   * moves through some bins → price impact.
   *
   * Estimate: |Y_actual - Y_target| / totalTvlY × 100, clamped 0..50%.
   * Y_target = X_in * currentPrice (the "balanced" Y for this X).
   *
   * This is a UI hint, not a precise quote. Real per-bin calc lives
   * in pool-engine (Sprint 13 backend swap-in: POST /api/v1/pools/
   * {id}/add-liquidity/quote).
   */
  const addPriceImpactPct: number | null = useMemo(() => {
    if (amountX == null || amountY == null || amountX <= 0 || amountY <= 0) return null
    const yTarget = amountX * (pool.currentPrice ?? 0)
    const imbalance = Math.abs(amountY - yTarget)
    if (imbalance === 0) return 0
    const tvlY = pool.totalTvlY || 1
    const pct = (imbalance / tvlY) * 100
    return Math.min(50, pct)
  }, [amountX, amountY, pool.currentPrice, pool.totalTvlY])

  return (
    <Space direction="vertical" size={14} style={{ width: '100%' }}>
      {success && (
        <Alert
          message={success}
          type="success"
          showIcon
          closable
          onClose={() => setSuccess(null)}
          style={{ borderRadius: 10 }}
        />
      )}
      {error && (
        <Alert
          message={error}
          type="error"
          showIcon
          closable
          onClose={() => setError(null)}
          style={{ borderRadius: 10 }}
        />
      )}

      {/* Amount */}
      <div>
        <Text type="secondary" style={{ fontSize: 11, letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: 500 }}>
          Сумма
        </Text>
        <Space direction="vertical" size={8} style={{ width: '100%', marginTop: 8 }}>
          <AmountField
            symbol={pool.tokenXSymbol}
            value={amountX}
            onChange={setAmountX}
            available={balanceX?.available ?? 0}
          />
          <AmountField
            symbol={pool.tokenYSymbol}
            value={amountY}
            onChange={setAmountY}
            available={balanceY?.available ?? 0}
          />
        </Space>
      </div>

      {/* Strategy with Meteora-style icon buttons */}
      <div>
        <Text type="secondary" style={{ fontSize: 11, letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: 500 }}>
          Стратегия
        </Text>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(3, 1fr)',
            gap: 6,
            marginTop: 8,
          }}
        >
          {STRATEGIES.map((s) => {
            const active = strategy === s.value
            return (
              <button
                key={s.value}
                type="button"
                onClick={() => setStrategy(s.value)}
                style={{
                  border: `1px solid ${active ? 'var(--sber-green)' : 'var(--border-light)'}`,
                  background: active ? 'rgba(33,160,56,0.08)' : '#fff',
                  borderRadius: 10,
                  padding: '10px 8px',
                  cursor: 'pointer',
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  gap: 6,
                  color: active ? 'var(--sber-green)' : 'var(--text-secondary)',
                  transition: 'all 0.15s',
                }}
              >
                <StrategyIcon kind={s.value} />
                <span style={{ fontSize: 12, fontWeight: 600 }}>{s.label}</span>
              </button>
            )
          })}
        </div>
      </div>

      {/* Price range — Meteora-style range slider + numeric inputs.
          Sprint 9-DS-r4 (P1-1) — added the draggable Min/Max slider
          on top of the existing numeric inputs and quick-picks. The
          slider gives at-a-glance feedback for the new LP; numerics
          stay for ops who need bin-level precision (and for inputs
          beyond the slider's ±50 view). */}
      <div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <Text type="secondary" style={{ fontSize: 11, letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: 500 }}>
            Диапазон цен
          </Text>
          <Space size={6}>
            {[5, 10, 20].map((width) => (
              <Button
                key={width}
                size="small"
                type="text"
                style={{ fontSize: 11, padding: '0 6px', height: 22, color: 'var(--sber-green)', fontWeight: 600 }}
                onClick={() => {
                  setBinMin(pool.activeBinId - width)
                  setBinMax(pool.activeBinId + width)
                }}
              >
                ±{width}
              </Button>
            ))}
          </Space>
        </div>

        {/* Sprint 9-DS-r4 (P1-1) — the visual price range. Slider
            range is ±50 around active (covers the SPOT/CURVE common
            case + headroom for BID_ASK); numeric inputs below can
            still go wider for power users. Tooltip on each handle
            shows the actual price (Y per 1 X) computed from the bin
            offset via the DLMM log-spaced formula — same math the
            backend's BinMath uses. */}
        <BinRangeSlider
          activeBinId={pool.activeBinId}
          binStep={pool.binStep}
          currentPrice={pool.currentPrice ?? 0}
          quoteSymbol={pool.tokenYSymbol}
          binMin={binMin}
          binMax={binMax}
          onChange={(min, max) => {
            setBinMin(min)
            setBinMax(max)
          }}
        />

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginTop: 12 }}>
          <div>
            <Text type="secondary" style={{ fontSize: 11 }}>Мин бин</Text>
            <InputNumber
              value={binMin}
              onChange={(v) => setBinMin(v)}
              style={{ width: '100%' }}
              max={pool.activeBinId}
            />
          </div>
          <div>
            <Text type="secondary" style={{ fontSize: 11 }}>Макс бин</Text>
            <InputNumber
              value={binMax}
              onChange={(v) => setBinMax(v)}
              style={{ width: '100%' }}
              min={pool.activeBinId}
            />
          </div>
        </div>
        <div style={{ marginTop: 8, fontSize: 11, color: 'var(--text-muted)' }}>
          Активный бин: <span style={{ fontFamily: 'JetBrains Mono, monospace' }}>{pool.activeBinId}</span>
          {totalBins > 0 && (
            <>
              {' · '}
              <Tag color="default" style={{ borderRadius: 999, fontSize: 10, padding: '0 6px', lineHeight: '16px' }}>
                {totalBins} бин{totalBins === 1 ? '' : totalBins < 5 ? 'а' : 'ов'}
              </Tag>
            </>
          )}
        </div>
      </div>

      {/* Sprint 9-DS-r4 (P1-7) — Meteora-style cost estimate. Always
          rendered (even with empty inputs) so the LP knows the form
          will surface costs; populates as soon as bins + amounts are
          set. Tooltip clarifies the per-bin number is a placeholder
          until real per-bin storage fees ship in Sprint 10. */}
      {totalBins > 0 && (
        <div
          style={{
            padding: '10px 12px',
            background: 'var(--surface-1, #F9FAFB)',
            borderRadius: 10,
            fontSize: 12,
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              Ваш капитал
              {pool.tokenYSymbol !== 'SRUB' && (
                <Tooltip title={`В единицах ${pool.tokenYSymbol}. Для не-SRUB пар без отдельной FX-конвертации.`}>
                  {' '}
                  <InfoCircleOutlined style={{ fontSize: 11, color: 'var(--text-muted)' }} />
                </Tooltip>
              )}
            </Text>
            <Text strong style={{ fontVariantNumeric: 'tabular-nums' }}>
              {capitalRub > 0 ? formatRub(capitalRub) : '—'}
            </Text>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              Учёт позиции ({totalBins} × {COST_PER_BIN_RUB} ₽)
              <Tooltip title="Оценочная стоимость учёта позиции в бухгалтерии пула. Замена реальной формулы — в Sprint 10 после аудита 1С.">
                {' '}
                <InfoCircleOutlined style={{ fontSize: 11, color: 'var(--text-muted)' }} />
              </Tooltip>
            </Text>
            <Text style={{ fontVariantNumeric: 'tabular-nums' }}>{formatRub(bookkeepingRub)}</Text>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              Комиссия протокола на вход
              <Tooltip title="Protocol fee на DLMM применяется к свопам, а не к вводу ликвидности. Поэтому всегда 0 на этом шаге.">
                {' '}
                <InfoCircleOutlined style={{ fontSize: 11, color: 'var(--text-muted)' }} />
              </Tooltip>
            </Text>
            <Text style={{ fontVariantNumeric: 'tabular-nums' }}>{formatRub(protocolFeeRub)}</Text>
          </div>

          {/* Sprint 12 G-22 — price impact on the act of adding
              liquidity itself. Dmitry-driven (medium-business demo):
              "На add-liquidity слип не рассчитывается, только на swap".
              Formula: if you're depositing X+Y where X/Y is not at
              currentPrice (target ratio), the deposit implicitly
              "swaps" to balance — that swap moves the bin. Estimate:
              max(deltaX, deltaY) / totalTvlSide × 100. Capped at 50%
              for display sanity. */}
          {addPriceImpactPct != null && addPriceImpactPct > 0.05 && (
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                Влияние на цену пула
                <Tooltip title="Если ваш ввод смещает пропорцию активного бина, цена пула сдвигается. Чем больше относительно TVL — тем больший сдвиг. Свыше 2% — рекомендуется разбить ввод на несколько частей.">
                  {' '}
                  <InfoCircleOutlined style={{ fontSize: 11, color: 'var(--text-muted)' }} />
                </Tooltip>
              </Text>
              <Text
                strong
                style={{
                  fontVariantNumeric: 'tabular-nums',
                  color: addPriceImpactPct > 2
                    ? 'var(--color-negative)'
                    : addPriceImpactPct > 0.5
                      ? 'var(--color-warning-amber)'
                      : 'var(--sber-green)',
                }}
              >
                {addPriceImpactPct.toFixed(2)}%
              </Text>
            </div>
          )}
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              paddingTop: 6,
              borderTop: '1px solid var(--border-light)',
            }}
          >
            <Text strong style={{ fontSize: 12 }}>Итого стоимость создания</Text>
            <Text strong style={{ fontSize: 12, color: 'var(--sber-green)', fontVariantNumeric: 'tabular-nums' }}>
              {formatRub(totalCostRub)}
            </Text>
          </div>
        </div>
      )}

      <Button
        type="primary"
        block
        size="large"
        className="sber-swap-cta"
        icon={<PlusOutlined />}
        disabled={!canAdd || addMutation.isPending}
        loading={addMutation.isPending}
        onClick={() => addMutation.mutate()}
      >
        {!amountX || !amountY
          ? 'Введите суммы'
          : !binMin || !binMax || binMax <= binMin
          ? 'Укажите диапазон'
          : 'Добавить ликвидность'}
      </Button>
    </Space>
  )
}

/**
 * Sprint 9-DS-r4 (P1-1) — Meteora-style draggable range slider for
 * the [binMin, binMax] selection. AntD's <Slider range/> handles the
 * UI; the formatter on each handle converts the bin offset into a
 * human-readable price using the DLMM log-spaced formula
 * {@code price = currentPrice × (1 + binStep/10000)^(binId - activeBinId)}.
 *
 * <p>Slider domain is fixed at ±50 bins around active — covers the
 * common SPOT/CURVE adds and gives BID_ASK enough headroom. Power
 * users who need a wider range use the numeric inputs below.
 */
function BinRangeSlider({
  activeBinId,
  binStep,
  currentPrice,
  quoteSymbol,
  binMin,
  binMax,
  onChange,
}: {
  activeBinId: number
  binStep: number
  currentPrice: number
  quoteSymbol: string
  binMin: number | null
  binMax: number | null
  onChange: (min: number, max: number) => void
}) {
  const RANGE = 50
  const sliderMin = activeBinId - RANGE
  const sliderMax = activeBinId + RANGE
  // Clamp the current value into the slider domain for display; the
  // numeric inputs below still hold the true value.
  const lo = Math.max(sliderMin, Math.min(sliderMax, binMin ?? activeBinId))
  const hi = Math.max(sliderMin, Math.min(sliderMax, binMax ?? activeBinId))

  const priceAtBin = (binId: number) => {
    if (!currentPrice || !binStep) return 0
    const r = 1 + binStep / 10_000
    return currentPrice * Math.pow(r, binId - activeBinId)
  }

  const fmt = (n: number) =>
    n >= 1000
      ? n.toLocaleString('ru-RU', { maximumFractionDigits: 2 })
      : n.toLocaleString('ru-RU', { maximumFractionDigits: 4 })

  return (
    <div style={{ padding: '0 6px' }}>
      <Slider
        range
        min={sliderMin}
        max={sliderMax}
        value={[lo, hi]}
        marks={{
          [activeBinId]: {
            label: (
              <span style={{ color: 'var(--sber-green)', fontSize: 10, fontWeight: 600 }}>
                ★ {currentPrice ? fmt(currentPrice) : 'актив'}
              </span>
            ),
            style: { color: 'var(--sber-green)' },
          },
        }}
        tooltip={{
          formatter: (binId) =>
            binId != null && currentPrice > 0
              ? `${fmt(priceAtBin(binId))} ${quoteSymbol}`
              : `bin ${binId}`,
        }}
        styles={{
          // Highlight the active-bin tick a touch greener than default.
          track: { backgroundColor: 'var(--sber-green)' },
        }}
        onChange={(v) => {
          if (Array.isArray(v) && v.length === 2) {
            onChange(v[0], v[1])
          }
        }}
      />
    </div>
  )
}

function AmountField({
  symbol,
  value,
  onChange,
  available,
}: {
  symbol: string
  value: number | null
  onChange: (v: number | null) => void
  available: number
}) {
  return (
    <div
      style={{
        background: 'var(--surface-1, #F9FAFB)',
        borderRadius: 12,
        padding: 12,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
        <Tag color="green" style={{ borderRadius: 999, padding: '2px 10px', margin: 0, fontWeight: 600 }}>
          {symbol}
        </Tag>
        <Space size={4}>
          <Text type="secondary" style={{ fontSize: 11 }}>
            Доступно: {formatCompact(available)}
          </Text>
          <Button
            type="link"
            size="small"
            style={{ padding: '0 4px', fontSize: 11, height: 18, fontWeight: 600 }}
            onClick={() => onChange(available)}
          >
            MAX
          </Button>
        </Space>
      </div>
      <InputNumber
        style={{ width: '100%', fontSize: 16, fontWeight: 600 }}
        variant="borderless"
        placeholder="0.0"
        value={value}
        onChange={(v) => onChange(v)}
        min={0}
        controls={false}
        formatter={(v) => (v ? Number(v).toLocaleString('ru-RU') : '')}
        parser={(v) => Number((v || '').toString().replace(/\s/g, '')) as 0}
      />
    </div>
  )
}
