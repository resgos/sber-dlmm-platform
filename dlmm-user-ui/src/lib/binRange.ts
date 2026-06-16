export interface BinRange { min: number; max: number }

/**
 * A symmetric bin range of `±halfWidth` bins around the pool's active bin —
 * the math behind the add-liquidity range presets, so an investor picks a
 * range in one click instead of typing raw bin indices. `min` is clamped to 0
 * (bin ids are non-negative) and inputs are floored to whole bins.
 */
export function presetBinRange(activeBinId: number, halfWidth: number): BinRange {
  const centre = Math.floor(activeBinId)
  const w = Math.max(0, Math.floor(halfWidth))
  return { min: Math.max(0, centre - w), max: centre + w }
}

/**
 * Preset half-widths offered on the add-liquidity form. Narrower = more
 * concentrated (higher fee share, higher out-of-range risk); wider = safer.
 * At a 25 bps bin step these span roughly ±1.25% / ±3.75% / ±10% in price.
 */
export const BIN_RANGE_PRESETS = [
  { halfWidth: 5 },
  { halfWidth: 15 },
  { halfWidth: 40 },
] as const
