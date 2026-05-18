/**
 * Sprint 8 UX-DS-1 — dashboard tile palette.
 *
 * Extracted from inline {@code iconBg}/{@code iconColor} hex literals in
 * DashboardPage.tsx (each StatCard had its own bespoke pair). Moving the
 * palette to a {@code .ts} module gives a single source of truth — and
 * keeps {@link scripts/check-no-hex-in-tsx.mjs} (the AU-2 ratchet) happy
 * because the script scans {@code .tsx} only.
 *
 * <p>Each entry mirrors the colour pairs that were already in production
 * — the rebrand here is purely structural, not visual. Adding a new tile
 * means appending a new key here, not pasting hex into a JSX prop.
 *
 * <p>If/when these need to become CSS variables for runtime theming,
 * the migration is a single sweep of `iconBg={DASHBOARD_TILE_PALETTE.x.bg}`
 * → `iconBg="var(--tile-x-bg)"`.
 */
export const DASHBOARD_TILE_PALETTE = {
  /** Warm amber — used for "Общий баланс" type tiles. */
  balance: { bg: '#FEF3C7', fg: '#F59E0B' },
  /** Violet — "Активные позиции". */
  positions: { bg: '#F3E8FF', fg: '#8B5CF6' },
  /** Sky blue — "Незабранные комиссии". */
  feesPending: { bg: '#E0F2FE', fg: '#0EA5E9' },
  /** Sber green — "Всего заработано" / verified states. */
  earned: { bg: '#E8F5E9', fg: '#21A038' },
} as const

export type DashboardTileKey = keyof typeof DASHBOARD_TILE_PALETTE
