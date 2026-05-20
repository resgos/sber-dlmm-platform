// Sprint 9 (post-DS-handoff) — shared formatters used across all
// admin pages. Lifted from the per-page `toLocaleString('ru-RU', ...)`
// pattern that was scattered across TransactionsPage / PoolsPage /
// DashboardPage. Centralising means one truth for thresholds + suffixes.

/**
 * Compact decimal formatter — used for "1.5K", "2.4M", "3.7B" style
 * shortenings on KPI tiles, pool reserves, transaction volumes.
 *
 * Russian locale uses тыс/млн/млрд/трлн/квд suffixes. Below 1 000 the
 * value is returned as-is with grouping. The fractional digit count
 * shrinks as the unit grows (big numbers don't need cents).
 */
export function formatCompact(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  const abs = Math.abs(value)
  if (abs >= 1_000_000_000_000_000) {
    return `${(value / 1_000_000_000_000_000).toFixed(2)} квд`
  }
  if (abs >= 1_000_000_000_000) {
    return `${(value / 1_000_000_000_000).toFixed(2)} трлн`
  }
  if (abs >= 1_000_000_000) {
    return `${(value / 1_000_000_000).toFixed(2)} млрд`
  }
  if (abs >= 1_000_000) {
    return `${(value / 1_000_000).toFixed(2)} млн`
  }
  if (abs >= 10_000) {
    return `${(value / 1_000).toFixed(1)} тыс`
  }
  return value.toLocaleString('ru-RU', { maximumFractionDigits: 2 })
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
 * Truncate a UUID to its last 6 chars with leading ellipsis — used
 * everywhere we render an ID in a table cell. The seed data shares
 * long prefixes ("a0000000-…" / "88000000-…") so the tail is what
 * the operator actually distinguishes rows by.
 */
export function shortId(id: string | null | undefined): string {
  if (!id) return '—'
  return `…${id.slice(-6)}`
}
