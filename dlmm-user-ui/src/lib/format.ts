// Sprint 9 (post-DS-handoff) — shared formatters used across all
// admin pages. Lifted from the per-page `toLocaleString('ru-RU', ...)`
// pattern that was scattered across TransactionsPage / PoolsPage /
// DashboardPage. Centralising means one truth for thresholds + suffixes.

import i18n from '@/i18n'

/**
 * Compact decimal formatter — used for "1.5K", "2.4M", "3.7B" style
 * shortenings on KPI tiles, pool reserves, transaction volumes.
 *
 * Locale-aware (audit B8/B10): EN shows K/M/B/T, RU shows тыс/млн/млрд/трлн/квд.
 * Below 10 000 the value is returned as-is with locale grouping. The fractional
 * digit count shrinks as the unit grows (big numbers don't need cents). Reads the
 * active i18n language; a language flip re-formats on the next render.
 */
export function formatCompact(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  const en = i18n.language === 'en'
  const locale = en ? 'en-US' : 'ru-RU'
  const sep = en ? '' : ' '
  const s = en
    ? { quad: 'Q', tril: 'T', bil: 'B', mil: 'M', thou: 'K' }
    : { quad: 'квд', tril: 'трлн', bil: 'млрд', mil: 'млн', thou: 'тыс' }
  const abs = Math.abs(value)
  if (abs >= 1_000_000_000_000_000) {
    return `${(value / 1_000_000_000_000_000).toFixed(2)}${sep}${s.quad}`
  }
  if (abs >= 1_000_000_000_000) {
    return `${(value / 1_000_000_000_000).toFixed(2)}${sep}${s.tril}`
  }
  if (abs >= 1_000_000_000) {
    return `${(value / 1_000_000_000).toFixed(2)}${sep}${s.bil}`
  }
  if (abs >= 1_000_000) {
    return `${(value / 1_000_000).toFixed(2)}${sep}${s.mil}`
  }
  if (abs >= 10_000) {
    return `${(value / 1_000).toFixed(1)}${sep}${s.thou}`
  }
  return value.toLocaleString(locale, { maximumFractionDigits: 2 })
}

/**
 * Compact rouble formatter — same as `formatCompact` but with " ₽" suffix.
 * Use on monetary KPIs (TVL, fee revenue, swap volumes).
 */
export function formatRub(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '— ₽'
  if (Math.abs(value) < 10_000) {
    return `${value.toLocaleString('ru-RU', { maximumFractionDigits: 2 })} ₽`
  }
  return `${formatCompact(value)} ₽`
}

/**
 * Token amount formatter — joins a raw amount with its decimal-aware
 * representation. Backend stores amounts as base units (raw integers)
 * but a couple of API endpoints have already pre-divided. Caller passes
 * what they have; this just controls precision and adds the symbol.
 *
 * For very large balances the result is compacted; for small balances
 * it's shown in full (so 0.0001 BTC doesn't get rounded to 0).
 */
export function formatTokenAmount(
  value: number | null | undefined,
  symbol?: string | null,
  opts: { maxFractionDigits?: number; compact?: boolean } = {},
): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  const { maxFractionDigits = 4, compact = true } = opts
  const abs = Math.abs(value)
  let body: string
  if (compact && abs >= 10_000) {
    body = formatCompact(value)
  } else {
    body = value.toLocaleString('ru-RU', { maximumFractionDigits: maxFractionDigits })
  }
  return symbol ? `${body} ${symbol}` : body
}

/**
 * Percent formatter — handles 0 → "0,00%", null → "—".
 */
export function formatPercent(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return `${value.toFixed(digits)}%`
}

/**
 * Adaptive rate-number formatter. A swap rate can be huge (1 SBTC ≈ 5 000 000 ₽)
 * or tiny (1 ₽ ≈ 0,0000002 SBTC), so plain toFixed(6) loses the small side and
 * over-pads the big side. Scale the precision to the magnitude, keeping ~4
 * significant figures for sub-1 values so the reverse rate stays visible.
 */
export function formatRateValue(v: number): string {
  if (!Number.isFinite(v) || v <= 0) return '—'
  if (v >= 1000) return v.toLocaleString('ru-RU', { maximumFractionDigits: 2 })
  if (v >= 1) return v.toLocaleString('ru-RU', { maximumFractionDigits: 4 })
  return Number(v.toPrecision(4)).toLocaleString('ru-RU', { maximumFractionDigits: 20 })
}

/**
 * Bidirectional exchange-rate strings for a swap quote — both
 * "1 In ≈ N Out" and the reverse "1 Out ≈ M In", so the user sees the rate
 * в обе стороны, not just one equivalent. Returns null if amounts are unusable.
 */
export function exchangeRatePair(
  amountIn: number | null | undefined,
  amountOut: number | null | undefined,
  symIn: string | null | undefined,
  symOut: string | null | undefined,
): { forward: string; reverse: string } | null {
  if (!amountIn || amountIn <= 0 || !amountOut || amountOut <= 0 || !symIn || !symOut) return null
  return {
    forward: `1 ${symIn} ≈ ${formatRateValue(amountOut / amountIn)} ${symOut}`,
    reverse: `1 ${symOut} ≈ ${formatRateValue(amountIn / amountOut)} ${symIn}`,
  }
}

/**
 * Truncate a UUID to its last 6 chars with leading ellipsis — used
 * everywhere we render an ID in a table cell. The seed data shares
 * long prefixes ("a0000000-…" / "88000000-…") so the tail is what
 * the operator actually distinguishes rows by.
 */
export function shortId(id: string | null | undefined): string {
  if (!id) return '—'
  return `…${id.slice(-6)}`
}
