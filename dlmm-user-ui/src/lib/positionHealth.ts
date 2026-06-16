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
//      Calibration: 20% APY → 1.0; 0% → 0; clamped. Below 0% (which
//      can't happen with fee yield, only IL) → 0.
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
// Calibration is deliberately conservative — APY targets and age
// thresholds are guesses we'll refine with real user data in Sprint 11.

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

// Fee-earning calibration constants — these are first-cut guesses.
// 20% annualised gross yield is "great" for the demo seed; real
// pools will land somewhere between 5–30%.
const TARGET_APY = 20
const MS_PER_YEAR = 365.25 * 24 * 60 * 60 * 1000

/**
 * Whether the pool's active bin currently sits inside the position's bin range —
 * i.e. the position is earning fees right now. Returns null when the pool isn't
 * loaded yet (can't tell). This is the single most actionable LP signal
 * (out-of-range = earning nothing), so it's surfaced as a glanceable badge on
 * the positions list, not only folded into the health score.
 */
export function isPositionInRange(position: Position, pool: Pool | undefined): boolean | null {
  if (!pool) return null
  return pool.activeBinId >= position.binRangeMin && pool.activeBinId <= position.binRangeMax
}

export function calculateHealth(position: Position, pool: Pool | undefined): HealthScore {
  // --- Factor 1: range fit ---
  let rangeFitRaw = 0
  let rangeReason = 'Нет данных о пуле — диапазон оценить невозможно'
  if (pool) {
    const inRange = isPositionInRange(position, pool) === true
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
    feeEarningRaw = Math.min(1, annualisedYieldPct / TARGET_APY)
    feeReason = `Доходность по комиссиям ≈ ${annualisedYieldPct.toFixed(1)}% годовых ` +
                `(цель ${TARGET_APY}%)`
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
    // --viz-up (not --sber-green-light): the latter is --brand-primary-soft, a
    // pale BG-tint that's near-invisible as TEXT on the white light-theme cell.
    // --viz-up is the theme-aware readable green (#0D8523 light / #2FBF50 dark).
    case 'good':      return 'var(--viz-up)'
    case 'fair':      return 'var(--sber-amber)'
    case 'poor':      return 'var(--plasma-critical)'
  }
}
