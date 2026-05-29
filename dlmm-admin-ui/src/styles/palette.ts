/**
 * Sprint 8 UX-DS-1 — admin dashboard tile palette.
 *
 * Same structural pattern as user-ui's palette.ts (cross-app component
 * dedup is Sprint 9+ workspaces work). Each tile's icon-bg / icon-fg
 * pair gets a semantic name so JSX reads `iconBg={PALETTE.users.bg}`
 * not `iconBg="#EFF6FF"`.
 *
 * Hex literals here are exempt from the AU-2 ratchet because the script
 * scans `.tsx` only — `.ts` palette modules are the source-of-truth
 * pattern this design encourages.
 */
export const ADMIN_TILE_PALETTE = {
  /** Sky blue — user-count tiles, transaction-rate tiles. */
  users: { bg: '#EFF6FF', fg: '#3B82F6' },
  /** Sber green — verified / earned / KYC-positive tiles. */
  verified: { bg: '#E8F5E9', fg: '#21A038' },
  /** Violet — pool-related tiles. */
  pools: { bg: '#F3E8FF', fg: '#8B5CF6' },
  /** Cyan/teal — active-pools sub-counter. */
  poolsActive: { bg: '#E0F2FE', fg: '#0EA5E9' },
  /** Warm amber — TVL / financial-headline tiles. */
  tvl: { bg: '#FEF3C7', fg: '#F59E0B' },
  /** Pink — 24h volume tiles. */
  volume: { bg: '#FCE7F3', fg: '#EC4899' },
} as const

export type AdminTileKey = keyof typeof ADMIN_TILE_PALETTE

/**
 * DS-02 — data-viz colours for the admin dashboard charts.
 *
 * recharts renders to SVG and cannot reliably resolve CSS `var(--…)` inside
 * <linearGradient>/stroke attributes, so chart colours must be literals. We
 * keep them HERE (a `.ts` module, exempt from the AU-2 hex ratchet which only
 * scans `.tsx`) so no chart component carries raw hex. Values mirror the
 * Claude Design mockup + sber-theme.css `--ds-*` tokens.
 */
export const DASH_VIZ = {
  /** Sber green — TVL line/area, positive sparklines, volume bars. */
  accent: '#21A038',
  /** Red — negative deltas + their sparklines (mockup --danger). */
  danger: '#D14343',
  /** Grey dashed prior-period line. */
  prior: '#B7BEC8',
  /** Muted axis tick text. */
  axis: '#8A93A0',
  /** Hairline chart grid. */
  grid: '#F0EEE6',
  /** Dark tooltip surface + its muted sub-text. */
  tooltipBg: '#14171A',
  tooltipSub: '#B7BEC8',
} as const

