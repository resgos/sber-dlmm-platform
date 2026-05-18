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
