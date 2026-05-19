/**
 * Sprint 9 — user-friendly formatters (admin-ui mirror).
 * See dlmm-user-ui/src/utils/format.ts for design rationale.
 * Cross-app dup is on the Sprint 10 workspaces refactor list (#R-39).
 */

export function bpsToPercent(bps: number | null | undefined): string {
  if (bps == null || isNaN(bps)) return '—'
  const pct = bps / 100
  const fixed = Number.isInteger(pct) ? pct.toFixed(0) : pct.toFixed(2).replace(/\.?0+$/, '')
  return `${fixed}%`
}

export const formatBpsPct = bpsToPercent
export const formatBinStep = bpsToPercent
