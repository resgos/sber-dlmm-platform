import type { LiquidityStrategy } from '@/api/types'
import i18n from '@/i18n'

/**
 * T-01 (2026-05-29) — single source of truth for LiquidityStrategy display
 * names. The raw enum values (SPOT / CURVE / BID_ASK) were leaking to users
 * in position/preview tables (<Tag>{strategy}</Tag>). The same term a user
 * picks in StrategySelector is shown everywhere afterwards.
 *
 * i18n (2026-06-16): names resolve through `strategy.<S>.name` so the term is
 * bilingual everywhere (selector, position tags, liquidity table); the RU
 * labels below are the fallback. Uses the i18n singleton (not a hook) because
 * this is a plain helper — components calling it already re-render on language
 * change (same pattern as txTypeLabel in TransactionsPage).
 */
export const STRATEGY_LABELS: Record<LiquidityStrategy, string> = {
  SPOT: 'Равномерная',
  CURVE: 'Концентрированная',
  BID_ASK: 'Двусторонняя',
}

export function strategyLabel(s: string | null | undefined): string {
  if (!s) return '—'
  const fallback = STRATEGY_LABELS[s as LiquidityStrategy] ?? s
  return i18n.t(`strategy.${s}.name`, { defaultValue: fallback })
}
