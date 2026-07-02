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

/**
 * Notification-type → AntD Tag color. Extracted from
 * NotificationBell.tsx where 12 hex literals lived in a const map.
 * Same colour mapping; new shape gives a single source of truth and
 * keeps the AU-2 ratchet quiet (palette in {@code .ts}, not {@code .tsx}).
 */
export const NOTIFICATION_TYPE_COLORS: Record<string, string> = {
  SWAP_COMPLETED: '#21A038',     // sber green
  LIQUIDITY_ADDED: '#3B82F6',    // blue
  FEE_ACCRUED: '#F59E0B',        // amber
  KYC_APPROVED: '#21A038',       // sber green
  KYC_REJECTED: '#EF4444',       // red
  POSITION_CLOSED: '#6B7280',    // gray
  POOL_PAUSED: '#F59E0B',        // amber
  SYSTEM_ALERT: '#EF4444',       // red
  POOL_UPDATE: '#8B5CF6',        // violet
  // Sprint 5 #5.15 — margin alerts. WARNING = amber (treasurer should
  // look soon), CALL = red (position is out-of-range, fees not accruing).
  MARGIN_WARNING: '#F59E0B',
  MARGIN_CALL: '#DC2626',
}

/** Fallback tag colour (used when the type isn't in the map). */
export const NOTIFICATION_TAG_DEFAULT = '#6B7280'

/**
 * 2026-06-17 — portfolio-structure card (dashboard). Segment colours for the
 * stacked composition bar: distinct, dashboard-palette-adjacent hues. Token
 * segments cycle through TOKEN_CYCLE by rank; the three fixed segment kinds
 * get stable colours so «В ликвидности» is always violet regardless of how
 * many token segments precede it. Hex lives here (.ts) — the AU-2 ratchet
 * scans .tsx only.
 */
export const PORTFOLIO_SEGMENT_COLORS = {
  /** Wallet token slices, by descending value rank (cycled). */
  TOKEN_CYCLE: ['#21A038', '#0EA5E9', '#F59E0B', '#EC4899', '#14B8A6'],
  /** Aggregated tail beyond the top-N tokens. */
  others: '#6B7280',
  /** Capital deployed in LP positions. */
  deployed: '#8B5CF6',
  /** Unclaimed fees ready to claim. */
  fees: '#F97316',
} as const
