import type { LiquidityStrategy } from '@/api/types'

/**
 * T-01 (2026-05-29) — single source of truth for LiquidityStrategy display
 * names. The raw enum values (SPOT / CURVE / BID_ASK) were leaking to users
 * in position/preview tables (<Tag>{strategy}</Tag>). These RU labels match
 * the StrategySelector cards so the term a user picks is the term they see
 * everywhere afterwards.
 */
export const STRATEGY_LABELS: Record<LiquidityStrategy, string> = {
  SPOT: 'Равномерная',
  CURVE: 'Концентрированная',
  BID_ASK: 'Двусторонняя',
}

export function strategyLabel(s: string | null | undefined): string {
  if (!s) return '—'
  return STRATEGY_LABELS[s as LiquidityStrategy] ?? s
}
