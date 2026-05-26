// Sprint 10 (new feature) — Position Health Score.
//
// A single 0-100 number that summarises whether an LP position is
// "doing its job". Pure-function; no side effects. Mounted on
// PositionsPage as a coloured badge per row plus a tooltip with the
// per-factor breakdown.
//
// Score is the weighted sum of three factors, all 0-1:
//
//   1. RANGE FIT (45% weight) — is the pool's activeBin inside the
//      position's [binMin, binMax]? Inside → 1.0; out of range → 0.
//      Out-of-range positions can't earn fees, so this dominates.
//
//   2. FEE EARNING (35%) — annualised fee yield vs initial deposit.
//      Calibration: target APY → 1.0; 0% → 0; clamped. Below 0%
//      (which can't happen with fee yield, only IL) → 0.
//
//   3. POSITION AGE (20%) — positions younger than 7 days get a
//      penalty because the fee-rate sample is noisy. >= 30 days → 1.0;
//      <= 1 day → 0.5; linear in between. This prevents a 1-hour-old
//      position from scoring 100% just because it landed in-range with
//      one swap of fees.
//
// Why these weights: range-fit dominates because nothing else matters
// if you're out of range. Fee earning is the second-most-actionable
// signal — drives "should I claim or rebalance?". Age is a confidence
// modifier, not a primary factor.
//
// NEW-4 (Batch #3, 2026-05-26) — the target APY used by factor #2
// is no longer a hard-coded 20%. PositionsPage now fetches a
// per-pool median realised APY from the new GET /pools/{id}/target-apy
// endpoint (backend: PoolApyCalibrationService) and passes it in via
// the `targetApy` option. Pools where the real APY is 8% will now
// rate an 8%-earning position as "healthy" instead of penalising it
// against an arbitrary 20% anchor. When the backend has no opinion
// (sparse pool, sample < 5, fetch failed) the caller is expected to
// pass `undefined` and the calculator falls back to the historical
// 20% default — so behaviour pre-NEW-4 is preserved on the
// pessimistic path.

import type { Position, Pool } from '@/api/types'

export interface HealthFactor {
  /** 0-100, weight * raw. */
  contribution: number
  /** Human-readable description shown in the tooltip. */
  reason: string
}

export interface HealthScore {
  /** Overall 0-100. Rounded to nearest integer for display. */
  total: number
  /** Per-factor breakdown for the tooltip. */
  factors: {
    rangeFit: HealthFactor
    feeEarning: HealthFactor
    age: HealthFactor
  }
  /** Coarse bucket for the colour ramp. */
  band: 'excellent' | 'good' | 'fair' | 'poor'
}

const WEIGHTS = { rangeFit: 0.45, feeEarning: 0.35, age: 0.20 } as const

// Fallback target APY (percentage points, e.g. 20 = 20%) when the caller
// doesn't pass a per-pool override. Mirrors the backend
// PoolApyCalibrationService.DEFAULT_TARGET_APY * 100. Kept here so the
// pure-function calculator stays usable without any backend dependency
// (unit tests, mock-API mode, fallback when the target-apy fetch fails).
export const DEFAULT_TARGET_APY = 20
const MS_PER_YEAR = 365.25 * 24 * 60 * 60 * 1000

/**
 * Extra knobs for the calculator. New options should default to "old
 * behaviour" so existing call-sites don't have to change unless they
 * care about the new feature.
 */
export interface HealthOptions {
  /**
   * NEW-4 — per-pool target APY override (percentage points, e.g.
   * {@code 8} for 8% APY). When omitted the calculator falls back to
   * {@link DEFAULT_TARGET_APY} (20%) for backwards compatibility with
   * the original Sprint 10 calibration. Pass the value returned by
   * GET /pools/{id}/target-apy converted from decimal to percent
   * (multiply by 100).
   */
  targetApy?: number
}

export function calculateHealth(
  position: Position,
  pool: Pool | undefined,
  options: HealthOptions = {},
): HealthScore {
  // NEW-4 — accept Number-finite, positive values only. Anything else
  // (NaN, 0, negative) falls back to the default so a misbehaving
  // backend response can't poison the score.
  const targetApy =
    typeof options.targetApy === 'number' &&
    Number.isFinite(options.targetApy) &&
    options.targetApy > 0
      ? options.targetApy
      : DEFAULT_TARGET_APY
  // --- Factor 1: range fit ---
  let rangeFitRaw = 0
  let rangeReason = 'Нет данных о пуле — диапазон оценить невозможно'
  if (pool) {
    const inRange = pool.activeBinId >= position.binRangeMin &&
                    pool.activeBinId <= position.binRangeMax
    if (inRange) {
      // Bonus for being centred: a position whose activeBin is in the
      // middle of [min,max] is more resilient to small price moves.
      // Centred → 1.0; at the edge → 0.85 (still in-range, still
      // earning, but one tick from going out).
      const span = position.binRangeMax - position.binRangeMin
      if (span <= 0) {
        rangeFitRaw = 1
        rangeReason = 'Активный бин совпадает с позицией (узкий диапазон)'
      } else {
        const centre = (position.binRangeMin + position.binRangeMax) / 2
        const distance = Math.abs(pool.activeBinId - centre)
        const normalisedDistance = distance / (span / 2) // 0=centre, 1=edge
        rangeFitRaw = 1 - 0.15 * normalisedDistance
        rangeReason = `Активный бин ${pool.activeBinId} внутри диапазона [${position.binRangeMin}, ${position.binRangeMax}]`
      }
    } else {
      rangeFitRaw = 0
      const distance = pool.activeBinId < position.binRangeMin
        ? position.binRangeMin - pool.activeBinId
        : pool.activeBinId - position.binRangeMax
      rangeReason = `Активный бин вне диапазона на ${distance} бинов — комиссии не начисляются`
    }
  }

  // --- Factor 2: fee earning ---
  // Annualise the realised fees: (unclaimed + claimed proxy) / initial / age * year.
  // We don't track claimed-per-position in the Position DTO (only sum-of-claimed
  // in fee-summary), so this is purely unclaimed-based — strictly conservative.
  // When the per-position claimed fees land (Sprint 11 — fee_accruals
  // reverse-join), this expression should switch to total realised, not just
  // unclaimed.
  let feeEarningRaw = 0
  let feeReason = 'Комиссии пока не начислены'
  const initial = (position.initialDepositX ?? 0) + (position.initialDepositY ?? 0)
  const realisedFees = position.unclaimedFeeX + position.unclaimedFeeY
  const ageMs = Date.now() - new Date(position.createdAt).getTime()
  if (initial > 0 && ageMs > 0 && realisedFees > 0) {
    const annualisedYieldPct = (realisedFees / initial) * (MS_PER_YEAR / ageMs) * 100
    feeEarningRaw = Math.min(1, annualisedYieldPct / targetApy)
    // Format the target without trailing zeros for whole values
    // (20 stays "20", 8.5 stays "8.5"). Plain toFixed(1) would render
    // "20.0", which looks unintentionally precise.
    const targetLabel = Number.isInteger(targetApy) ? targetApy.toString() : targetApy.toFixed(1)
    feeReason = `Доходность по комиссиям ≈ ${annualisedYieldPct.toFixed(1)}% годовых ` +
                `(цель ${targetLabel}%)`
  }

  // --- Factor 3: age ---
  const ageDays = ageMs / (24 * 60 * 60 * 1000)
  let ageRaw: number
  let ageReason: string
  if (ageDays >= 30) {
    ageRaw = 1
    ageReason = `Позиции ${Math.floor(ageDays)} дн — выборка комиссий устойчивая`
  } else if (ageDays >= 7) {
    // Linear: 7 days → 0.7, 30 days → 1.0.
    ageRaw = 0.7 + (ageDays - 7) / (30 - 7) * 0.3
    ageReason = `Позиции ${Math.floor(ageDays)} дн — выборка набирает устойчивость`
  } else if (ageDays >= 1) {
    // Linear: 1 day → 0.5, 7 days → 0.7.
    ageRaw = 0.5 + (ageDays - 1) / (7 - 1) * 0.2
    ageReason = `Позиции ${Math.floor(ageDays)} дн — выборка небольшая`
  } else {
    ageRaw = 0.5
    ageReason = 'Позиции меньше суток — оценка предварительная'
  }

  // --- Combine ---
  const rangeFitC = WEIGHTS.rangeFit * rangeFitRaw * 100
  const feeEarningC = WEIGHTS.feeEarning * feeEarningRaw * 100
  const ageC = WEIGHTS.age * ageRaw * 100
  const total = Math.round(rangeFitC + feeEarningC + ageC)

  let band: HealthScore['band']
  if (total >= 80) band = 'excellent'
  else if (total >= 60) band = 'good'
  else if (total >= 35) band = 'fair'
  else band = 'poor'

  return {
    total,
    band,
    factors: {
      rangeFit:   { contribution: Math.round(rangeFitC), reason: rangeReason },
      feeEarning: { contribution: Math.round(feeEarningC), reason: feeReason },
      age:        { contribution: Math.round(ageC), reason: ageReason },
    },
  }
}

/**
 * Map a health band to the CSS variable used for badge tinting.
 * Keeps the colour decision in one place — easy to retheme.
 */
export function bandColor(band: HealthScore['band']): string {
  switch (band) {
    case 'excellent': return 'var(--sber-green)'
    case 'good':      return 'var(--sber-green-light)'
    case 'fair':      return 'var(--sber-amber)'
    case 'poor':      return 'var(--plasma-critical)'
  }
}
