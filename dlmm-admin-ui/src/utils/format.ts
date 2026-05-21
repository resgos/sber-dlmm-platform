/**
 * Sprint 9 — user-friendly formatters.
 *
 * Replaces raw fintech jargon (bps, bin ID, micros) in user-facing UI
 * with concepts a non-technical treasurer or retail user can read.
 */

/**
 * Convert basis points (1/100 of a percent) to a percent string.
 *   30 → "0.3%"
 *   25 → "0.25%"
 *   100 → "1%"
 *   2500 → "25%"
 *
 * Trims trailing zeros for readability — "0.30%" → "0.3%". Always
 * shows at least one decimal for values < 100, none for whole percents.
 */
export function bpsToPercent(bps: number | null | undefined): string {
  if (bps == null || isNaN(bps)) return '—'
  const pct = bps / 100
  // Whole percents get no decimal; fractional gets up to 2.
  const fixed = Number.isInteger(pct) ? pct.toFixed(0) : pct.toFixed(2).replace(/\.?0+$/, '')
  return `${fixed}%`
}

/**
 * Same as bpsToPercent but with a unit suffix already attached.
 * Useful in table cells where the cell title doesn't carry the unit:
 *   formatBpsPct(30) → "0.3%"
 */
export const formatBpsPct = bpsToPercent

/**
 * Format bin step as "шаг X%" — the user-facing version of
 * "bin step 25". Plain user reads "цена меняется на 0.25% между
 * соседними бинами" instead of having to decode bps.
 */
export function formatBinStep(bps: number | null | undefined): string {
  return bpsToPercent(bps)
}
