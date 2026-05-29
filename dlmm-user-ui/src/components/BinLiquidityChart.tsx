import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  BarChart, Bar, Cell, XAxis, YAxis, CartesianGrid, Tooltip, ReferenceLine, ResponsiveContainer,
} from 'recharts'
import { Spin, Empty, Alert, Button, Space, Tooltip as AntTooltip } from 'antd'
import { ZoomInOutlined, ZoomOutOutlined, AimOutlined } from '@ant-design/icons'
import { pools as poolService } from '@/api/services'
import { calculateStrategyWeights } from '@/lib/strategyWeights'
import type { LiquidityStrategy } from '@/api/types'

// Sprint 9-DS-r4 (P1-5) — Meteora-style zoom levels for the bin
// chart. Number = half-window radius around the active bin (so
// effective view is ±radius bins). Start at ±25 (the previous
// hardcoded value); two zoom-out steps reach the full distribution
// edge for most seed pools (21 bins per side); zoom-in tightens
// to ±10 / ±5 for fine-tuning a Curve-strategy add.
const ZOOM_LEVELS = [5, 10, 25, 50, 100] as const
const DEFAULT_ZOOM_INDEX = 2 // ±25, matches the pre-Sprint-9-DS-r4 default

interface BinLiquidityChartProps {
  poolId: string
  /**
   * Sprint 9-DS-r3 — when set, bins inside any of these {min,max}
   * ranges are highlighted in purple with a dashed outline so the
   * LP can see exactly where their liquidity sits relative to the
   * active bin and the pool's wider distribution. Mirror of
   * Meteora's "your bins" overlay on the Dynamic Terminal chart.
   */
  userBinRanges?: Array<{ binMin: number; binMax: number }>
  /**
   * Sprint 9-DS-r4 (P1-2) — live preview of the position the user
   * is composing in the Add Liquidity panel. Bins in this range
   * render an orange overlay bar at a height proportional to the
   * strategy weight (SPOT = flat, CURVE = bell, BID_ASK = U).
   * Cleared on tab switch or unmount.
   */
  pendingPreview?: {
    binMin: number
    binMax: number
    strategy: LiquidityStrategy
  } | null
  /**
   * Sprint 9-DS-r4 (P2-6) — user's share of this bin's total
   * liquidity, as a percentage. Used by the tooltip to surface
   * "Ваша доля: X%" so the LP can see where their concentration
   * sits relative to the pool depth. Approximation (uses strategy
   * weights, not actual per-bin liquidity_shares); exact value
   * comes from PositionResponse.binAllocations which we don't
   * currently fetch on this page.
   */
  userBinSharePctByBinId?: Map<number, number>
}

interface ChartDataPoint {
  binId: number
  price: string
  reserveX: number
  reserveY: number
  liquidity: number
  isActive: boolean
  isMine: boolean
  side: 'left' | 'active' | 'right'
}

/**
 * F-02 (UX-FINDINGS 2026-05-26) — compact number formatter для tooltip.
 *
 * Without this, raw `Number.toLocaleString('ru-RU')` renders 14-digit
 * values like "14 072 727 272 727" inside a hover tooltip, which combined
 * with ~50 simultaneously-rendered SVG bars frozes the browser. Compact
 * form ("14 трлн") fits in one line, no measurement explosion, no freeze.
 */
function formatCompact(value: number): string {
  const abs = Math.abs(value)
  if (abs >= 1e12) return `${(value / 1e12).toFixed(2)} трлн`
  if (abs >= 1e9) return `${(value / 1e9).toFixed(2)} млрд`
  if (abs >= 1e6) return `${(value / 1e6).toFixed(2)} млн`
  if (abs >= 1e3) return `${(value / 1e3).toFixed(1)} тыс`
  if (abs >= 1) return value.toLocaleString('ru-RU', { maximumFractionDigits: 4 })
  return value.toLocaleString('ru-RU', { maximumFractionDigits: 6 })
}

function getBinColor(
  side: 'left' | 'active' | 'right',
  distance: number,
  maxDist: number,
  isMine: boolean,
) {
  if (isMine) {
    // Sprint 9-DS-r3 — user's own bins overlay: purple (Plasma
    // accent-violet) so they stand out against the green/blue
    // pool-wide bars.
    return side === 'active' ? '#9333EA' : 'rgba(147, 51, 234, 0.85)'
  }
  if (side === 'active') return '#F59E0B'
  const t = Math.min(distance / Math.max(maxDist, 1), 1)
  const opacity = Math.max(0.25, 1 - t * 0.6)
  if (side === 'left') {
    const r = Math.round(59 + t * 20)
    const g = Math.round(130 - t * 40)
    return `rgba(${r},${g},246,${opacity})`
  }
  const g = Math.round(160 - t * 40)
  return `rgba(33,${g},56,${opacity})`
}

// Sprint 9 — tooltip uses real token symbols (tokenX/tokenYSymbol) instead
// of opaque "Резерв X / Y". Bin ID hidden — user sees price, not the
// internal index. "Активный" labelled "Текущая цена" because that's what
// the bin actually means to a non-quant user.
//
// Sprint 9-DS-r4 (P2-6) — tooltip now also surfaces:
//   - the bin id (in a small monospace tag, so power users can
//     correlate to the addLiquidity numeric range)
//   - "Активный бин" / "Ваш бин" badges when applicable
//   - "Ваша доля: X%" computed from the userBinSharePctByBinId map
// per the backlog spec.
const makeTooltip = (
  xSym: string,
  ySym: string,
  userBinSharePctByBinId?: Map<number, number>,
) => ({ active, payload }: any) => {
  if (!active || !payload?.length) return null
  const d: ChartDataPoint = payload[0]?.payload
  const userSharePct = d?.binId != null ? userBinSharePctByBinId?.get(d.binId) : undefined
  return (
    <div style={{
      background: '#fff', border: '1px solid #E5E7EB', borderRadius: 'var(--radius-sm)',
      padding: '10px 14px', fontSize: 'var(--text-xs)', boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
      minWidth: 200,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
        {d?.isActive && (
          <span style={{ background: '#FEF3C7', color: '#D97706', fontSize: 10,
            padding: '1px 6px', borderRadius: 'var(--radius-pill)', fontWeight: 600 }}>
            ★ Активный бин
          </span>
        )}
        {d?.isMine && !d?.isActive && (
          <span style={{ background: 'rgba(147,51,234,0.12)', color: '#7C3AED', fontSize: 10,
            padding: '1px 6px', borderRadius: 'var(--radius-pill)', fontWeight: 600 }}>
            Ваш бин
          </span>
        )}
        <span style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 10,
          color: '#9CA3AF' }}>
          #{d?.binId}
        </span>
      </div>
      <div style={{ fontWeight: 600, marginBottom: 4, color: d?.isActive ? '#D97706' : '#111827' }}>
        Цена: {d?.price}
      </div>
      <div style={{ color: '#3B82F6' }}>
        Резерв {ySym}: {formatCompact(d?.reserveY ?? 0)}
      </div>
      <div style={{ color: '#21A038' }}>
        Резерв {xSym}: {formatCompact(d?.reserveX ?? 0)}
      </div>
      <div style={{ color: '#9CA3AF', marginTop: 4 }}>
        Ликвидность: {formatCompact(d?.liquidity ?? 0)}
      </div>
      {userSharePct != null && userSharePct > 0 && (
        <div style={{ marginTop: 6, paddingTop: 6, borderTop: '1px solid #F3F4F6',
          color: '#7C3AED', fontWeight: 600 }}>
          Ваша доля: {userSharePct < 0.01 ? '< 0,01' : userSharePct.toFixed(2)}%
        </div>
      )}
    </div>
  )
}

export default function BinLiquidityChart({ poolId, userBinRanges, pendingPreview, userBinSharePctByBinId }: BinLiquidityChartProps) {
  // Sprint 9-DS-r4 (P1-5) — Meteora-style zoom level. Persisted only
  // for this render; no localStorage so different pools don't surprise
  // the user with a tight zoom that doesn't fit their layout.
  const [zoomIndex, setZoomIndex] = useState<number>(DEFAULT_ZOOM_INDEX)
  const windowRadius = ZOOM_LEVELS[zoomIndex]

  const { data: pool, isLoading, error } = useQuery({
    queryKey: ['poolDetail', poolId],
    queryFn: () => poolService.getPool(poolId),
    refetchInterval: 10000,
    enabled: !!poolId,
  })

  const isMineFn = (binId: number) => {
    if (!userBinRanges?.length) return false
    return userBinRanges.some((r) => binId >= r.binMin && binId <= r.binMax)
  }

  if (isLoading) return <div style={{ textAlign: 'center', padding: '40px 0' }}><Spin tip="Загрузка бинов..." /></div>
  if (error) return <Alert message="Не удалось загрузить данные бинов" type="error" showIcon style={{ borderRadius: 'var(--radius-sm)' }} />
  if (!pool?.bins || pool.bins.length === 0) return <Empty description="Нет данных по бинам" />

  const activeIdx = pool.bins.findIndex((b) => b.binId === pool.activeBinId)
  const effectiveActiveIdx = activeIdx === -1 ? Math.floor(pool.bins.length / 2) : activeIdx

  // Sprint 9-DS-r4 (P1-5) — slice driven by windowRadius (was a
  // hardcoded ±25). When the user has bins outside the current
  // window, the chart auto-extends just enough to include them so
  // the "ваши бины" overlay never goes missing on zoom-in.
  const userMinBin = userBinRanges?.length
    ? Math.min(...userBinRanges.map((r) => r.binMin))
    : pool.activeBinId
  const userMaxBin = userBinRanges?.length
    ? Math.max(...userBinRanges.map((r) => r.binMax))
    : pool.activeBinId
  const userMinIdx = pool.bins.findIndex((b) => b.binId === userMinBin)
  const userMaxIdx = pool.bins.findIndex((b) => b.binId === userMaxBin)

  let startIdx = Math.max(0, effectiveActiveIdx - windowRadius)
  let endIdx = Math.min(pool.bins.length, effectiveActiveIdx + windowRadius + 1)
  if (userMinIdx !== -1) startIdx = Math.min(startIdx, userMinIdx)
  if (userMaxIdx !== -1) endIdx = Math.max(endIdx, userMaxIdx + 1)

  // Sprint 9-DS-r4 (P1-2) — also extend the slice to cover the
  // pending preview range so the user sees the full overlay even
  // when they pick a wider range than the current zoom level.
  if (pendingPreview) {
    const pMinIdx = pool.bins.findIndex((b) => b.binId === pendingPreview.binMin)
    const pMaxIdx = pool.bins.findIndex((b) => b.binId === pendingPreview.binMax)
    if (pMinIdx !== -1) startIdx = Math.min(startIdx, pMinIdx)
    if (pMaxIdx !== -1) endIdx = Math.max(endIdx, pMaxIdx + 1)
  }
  const sliced = pool.bins.slice(startIdx, endIdx)

  // Sprint 9-DS-r4 (P1-2) — strategy-weighted preview, computed on
  // the same math as the backend's calculateDistributionWeights so
  // the overlay matches what would actually be deposited. Indexed by
  // (binId - binMin) inside the preview range; 0 outside.
  const previewWeights = pendingPreview
    ? calculateStrategyWeights(
        pendingPreview.strategy,
        pendingPreview.binMin,
        pendingPreview.binMax,
        pool.activeBinId,
      )
    : []

  const slicedActiveIdx = sliced.findIndex((b) => b.binId === pool.activeBinId)
  const maxLeft = slicedActiveIdx
  const maxRight = sliced.length - 1 - slicedActiveIdx

  // Sprint 9-DS-r4 (P1-2) — peak liquidity in the visible slice; the
  // preview overlay scales against this so even a CURVE on an empty
  // pool renders visibly (not a 0-height bar against a 1B-liquidity
  // peak).
  const peakLiquidity = sliced.reduce(
    (m, b) => (b.liquidity > m ? b.liquidity : m),
    1,
  )

  const chartData = sliced.map((bin, i) => {
    let side: 'left' | 'active' | 'right' = 'right'
    if (bin.binId === pool.activeBinId) side = 'active'
    else if (i < slicedActiveIdx) side = 'left'
    const distance = Math.abs(i - slicedActiveIdx)
    const maxDist = side === 'left' ? maxLeft : maxRight

    // Preview overlay value at this bin. Scaled to peakLiquidity so
    // the preview is visible against the actual pool distribution.
    let previewValue = 0
    if (pendingPreview && bin.binId >= pendingPreview.binMin && bin.binId <= pendingPreview.binMax) {
      const idx = bin.binId - pendingPreview.binMin
      previewValue = (previewWeights[idx] ?? 0) * peakLiquidity
    }

    return {
      binId: bin.binId, price: bin.price.toFixed(2),
      reserveX: Number(bin.reserveX.toFixed(4)), reserveY: Number(bin.reserveY.toFixed(4)),
      liquidity: Number(bin.liquidity.toFixed(0)),
      preview: Number(previewValue.toFixed(0)),
      isActive: bin.binId === pool.activeBinId,
      isMine: isMineFn(bin.binId),
      side, _distance: distance, _maxDist: maxDist,
    } as any
  })

  // Sprint 9 — replace "Токен X/Y" jargon with real symbols.
  const xSym = pool.tokenXSymbol || 'токен X'
  const ySym = pool.tokenYSymbol || 'токен Y'
  const activePrice = pool.bins.find((b) => b.binId === pool.activeBinId)?.price
  const Tooltip2 = makeTooltip(xSym, ySym, userBinSharePctByBinId)

  return (
    <div>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 16,
          marginBottom: 12,
          fontSize: 'var(--text-xs)',
          color: '#6B7280',
          flexWrap: 'wrap',
          justifyContent: 'space-between',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 20, flexWrap: 'wrap' }}>
          <span><span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 2, background: '#3B82F6', marginRight: 6, verticalAlign: 'middle' }} />Резерв {ySym}</span>
          <span><span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 2, background: '#F59E0B', marginRight: 6, verticalAlign: 'middle' }} />
            Текущая цена{activePrice != null ? `: ${activePrice.toFixed(4)}` : ''}
          </span>
          <span><span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 2, background: '#21A038', marginRight: 6, verticalAlign: 'middle' }} />Резерв {xSym}</span>
          {userBinRanges?.length ? (
            <span>
              <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 2, background: 'rgba(147,51,234,0.85)', marginRight: 6, verticalAlign: 'middle' }} />
              Ваши бины
            </span>
          ) : null}
          {pendingPreview ? (
            <span style={{ fontWeight: 600 }}>
              <span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: 2, background: 'rgba(245,158,11,0.55)', marginRight: 6, verticalAlign: 'middle', border: '1px dashed #D97706' }} />
              Превью {pendingPreview.strategy}
            </span>
          ) : null}
        </div>

        {/* Sprint 9-DS-r4 (P1-5) — Meteora-style zoom controls.
            ± steps through ZOOM_LEVELS; the centre button resets to
            the default ±25. Reset is also reachable via aim icon to
            mirror Meteora's "recenter on active" gesture. */}
        <Space size={4}>
          <AntTooltip title="Приблизить">
            <Button
              size="small"
              type="text"
              icon={<ZoomInOutlined />}
              disabled={zoomIndex === 0}
              onClick={() => setZoomIndex(Math.max(0, zoomIndex - 1))}
            />
          </AntTooltip>
          <AntTooltip title="К текущей цене">
            <Button
              size="small"
              type="text"
              icon={<AimOutlined />}
              onClick={() => setZoomIndex(DEFAULT_ZOOM_INDEX)}
            />
          </AntTooltip>
          <AntTooltip title="Отдалить">
            <Button
              size="small"
              type="text"
              icon={<ZoomOutOutlined />}
              disabled={zoomIndex === ZOOM_LEVELS.length - 1}
              onClick={() => setZoomIndex(Math.min(ZOOM_LEVELS.length - 1, zoomIndex + 1))}
            />
          </AntTooltip>
          <span style={{ fontSize: 'var(--text-xs)', color: '#9CA3AF', minWidth: 48, textAlign: 'right' }}>
            ±{windowRadius}
          </span>
        </Space>
      </div>
      <ResponsiveContainer width="100%" height={320}>
        <BarChart data={chartData} margin={{ top: 10, right: 20, left: 0, bottom: 20 }} barCategoryGap="4%">
          <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#F3F4F6" />
          <XAxis dataKey="binId" tick={false} axisLine={{ stroke: '#E5E7EB' }}
            label={{ value: `← ниже цены   |   ${activePrice != null ? activePrice.toFixed(4) : 'текущая цена'}   |   выше цены →`, position: 'insideBottom', offset: -8, fill: '#9CA3AF', fontSize: 'var(--text-xs)' }} />
          <YAxis tick={{ fontSize: 'var(--text-xs)', fill: '#9CA3AF' }} axisLine={false} tickLine={false}
            width={60}
            tickFormatter={(v) => {
              // Sprint 9-DS-r4 (P2-5) — previous formatter produced
              // 5-digit M-prefixed strings like "12000.0M" for
              // billion-scale TVL bins, which overflowed the narrow
              // default 45px Y-axis column and rendered as "2000.0M"
              // (leading "1" clipped). Fix: extend to B (billions)
              // and T (trillions) prefixes, drop the decimal for
              // values >= 100 in any band, and widen the axis to 60px.
              // F-03 (UX-FINDINGS 2026-05-26) — RU suffixes так что "16.0T"
              // не вызывает вопрос «Tons? Trillion? Tokens?». Match the
              // tooltip's formatCompact for consistency.
              const abs = Math.abs(v)
              if (abs >= 1e12) return `${(v / 1e12).toFixed(abs >= 1e14 ? 0 : 1)} трлн`
              if (abs >= 1e9) return `${(v / 1e9).toFixed(abs >= 1e11 ? 0 : 1)} млрд`
              if (abs >= 1e6) return `${(v / 1e6).toFixed(abs >= 1e8 ? 0 : 1)} млн`
              if (abs >= 1e3) return `${(v / 1e3).toFixed(0)} тыс`
              return String(v)
            }} />
          <Tooltip content={Tooltip2} cursor={{ fill: 'rgba(0,0,0,0.04)' }} />
          <ReferenceLine x={pool.activeBinId} stroke="#F59E0B" strokeWidth={2} strokeDasharray="5 3" />
          {/* F-02 root-cause fix (2026-05-27 review) — isAnimationActive={false}.
              Recharts Bar enter-animation runs on EVERY re-render; with ~50
              bars × Cells × 2 series, plus ResponsiveContainer re-measuring
              on scroll/resize, the animation frames pile up and block the
              main thread (browser froze on scroll during demo review). The
              tooltip compact-format fix earlier addressed one symptom; this
              kills the actual perf cliff. Bars are static distribution data —
              no animation needed. */}
          <Bar dataKey="liquidity" radius={[3, 3, 0, 0]} maxBarSize={18} isAnimationActive={false}>
            {chartData.map((entry: any, index: number) => (
              <Cell key={`cell-${index}`}
                fill={getBinColor(entry.side, entry._distance, entry._maxDist, entry.isMine)}
                stroke={entry.isActive ? '#D97706' : entry.isMine ? '#7C3AED' : 'none'}
                strokeWidth={entry.isActive ? 2 : entry.isMine ? 1.5 : 0}
                strokeDasharray={entry.isMine && !entry.isActive ? '3 2' : undefined}
              />
            ))}
          </Bar>
          {/* Sprint 9-DS-r4 (P1-2) — strategy-preview overlay. Drawn
              as a separate Bar series in semi-transparent orange so
              SPOT/CURVE/BID_ASK shapes are immediately visible against
              the existing pool distribution. */}
          {pendingPreview && (
            <Bar dataKey="preview" radius={[3, 3, 0, 0]} maxBarSize={18} isAnimationActive={false} fill="rgba(245,158,11,0.55)" stroke="#D97706" strokeWidth={1} strokeDasharray="3 2" />
          )}
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
