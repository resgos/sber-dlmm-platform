// PC-02 (2026-05-29, FEATURE-BATCH-PLAN) — bridge between the CSS
// data-viz tokens (`--viz-*` in sber-theme.css) and lightweight-charts,
// which needs concrete colour strings (it can't read `var(--x)`).
//
// We resolve the tokens off `getComputedStyle(:root)` once per call.
// Callers re-invoke this whenever the theme flips (the chart subscribes
// to themeStore via useSyncExternalStore and re-applies series options),
// so the candles/grid/axis recolour for light↔dark without a remount.
//
// Single source of truth: every colour here comes from a token defined
// in sber-theme.css under BOTH `:root` and `[data-theme="dark"]`. NO
// hardcoded hex/rgb fallbacks that would bypass the token system — the
// fallbacks below are only a last-resort guard for the (impossible in
// our SPA) case where the stylesheet hasn't applied yet, and they mirror
// the light-theme token values so a flash, if any, stays on-brand.

export interface VizPalette {
  /** candle up / line up / bid */
  up: string
  upSoft: string
  /** candle down / ask */
  down: string
  downSoft: string
  /** grid lines */
  grid: string
  /** axis text + border */
  axis: string
  /** external-market reference line (dashed) */
  external: string
  /** chart surface text (axis labels) */
  text: string
  /** chart surface bg — transparent so the Card bg shows through */
  background: string
}

/**
 * Read a CSS custom property off the document root, trimmed. Falls back
 * to {@code fallback} only if the property is empty (stylesheet not yet
 * applied — never happens after first paint in our SPA).
 */
function cssVar(name: string, fallback: string): string {
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return fallback
  }
  const v = getComputedStyle(document.documentElement)
    .getPropertyValue(name)
    .trim()
  return v || fallback
}

/**
 * Resolve the current data-viz palette from the live CSS tokens. Re-read
 * on every theme change. Fallbacks mirror the light-theme token values
 * in sber-theme.css so there is no off-brand flash before styles apply.
 */
export function resolveVizPalette(): VizPalette {
  return {
    up: cssVar('--viz-up', '#0D8523'),
    upSoft: cssVar('--viz-up-soft', 'rgba(13, 133, 35, 0.12)'),
    down: cssVar('--viz-down', '#E31227'),
    downSoft: cssVar('--viz-down-soft', 'rgba(227, 18, 39, 0.12)'),
    // --viz-grid / --viz-axis are themselves `var(--border-light)` /
    // `var(--text-muted)` aliases; getComputedStyle resolves the alias
    // chain to the final rgba for us.
    grid: cssVar('--viz-grid', 'rgba(8, 8, 8, 0.10)'),
    axis: cssVar('--viz-axis', 'rgba(8, 8, 8, 0.28)'),
    external: cssVar('--viz-external', '#6E5BFF'),
    text: cssVar('--text-secondary', 'rgba(8, 8, 8, 0.56)'),
    background: 'transparent',
  }
}
