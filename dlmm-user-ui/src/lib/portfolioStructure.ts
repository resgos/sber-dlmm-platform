import type { TokenBalance } from '@/api/types'

/**
 * 2026-06-17 — «Структура портфеля» (dashboard). Pure decomposition of the
 * hero's portfolio total into legible slices: top-N wallet tokens by ₽ value,
 * an aggregated «Прочие» tail, the LP-deployed capital and unclaimed fees.
 *
 * Inputs are the exact same figures the hero already computes (balances ×
 * rubPriceBySymbol, portfolioValueQuote, feeSummary.totalUnclaimed), so the
 * bar always sums to the headline — one source, no drift.
 */

export type PortfolioSegmentKind = 'token' | 'others' | 'deployed' | 'fees'

export interface PortfolioSegment {
  kind: PortfolioSegmentKind
  /** Token symbol for kind='token'; ignored for the fixed kinds (label comes from i18n). */
  symbol?: string
  /** ₽ value of the slice. */
  value: number
  /** Share of the grand total, 0–100 (2 decimals). */
  pct: number
}

export function buildPortfolioSegments(
  balances: Array<Pick<TokenBalance, 'symbol' | 'available' | 'locked'>>,
  priceBySymbol: Map<string, number>,
  deployedRub: number,
  unclaimedFeesRub: number,
  topN = 3,
): PortfolioSegment[] {
  // Wallet value per token; unpriced tokens contribute 0 (same as the hero).
  const tokenValues = balances
    .map((b) => ({ symbol: b.symbol, value: (b.available + b.locked) * (priceBySymbol.get(b.symbol) ?? 0) }))
    .filter((tv) => tv.value > 0)
    .sort((a, b) => b.value - a.value)

  const top = tokenValues.slice(0, topN)
  const othersValue = tokenValues.slice(topN).reduce((s, tv) => s + tv.value, 0)

  const total = tokenValues.reduce((s, tv) => s + tv.value, 0) + deployedRub + unclaimedFeesRub
  if (total <= 0) return []

  const pct = (v: number) => Math.round((v / total) * 10000) / 100

  const segments: PortfolioSegment[] = top.map((tv) => ({
    kind: 'token' as const,
    symbol: tv.symbol,
    value: tv.value,
    pct: pct(tv.value),
  }))
  if (othersValue > 0) segments.push({ kind: 'others', value: othersValue, pct: pct(othersValue) })
  if (deployedRub > 0) segments.push({ kind: 'deployed', value: deployedRub, pct: pct(deployedRub) })
  if (unclaimedFeesRub > 0) segments.push({ kind: 'fees', value: unclaimedFeesRub, pct: pct(unclaimedFeesRub) })
  return segments
}
