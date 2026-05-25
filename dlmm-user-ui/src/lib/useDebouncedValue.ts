import { useEffect, useState } from 'react'

/**
 * Sprint 11 G-22 — small debounce hook. While inputs are changing
 * rapidly (typing into amount fields, dragging a slider), defer the
 * "settled" value by `delayMs` so downstream effects (network calls,
 * expensive memos) only fire after the user pauses.
 *
 * <p>Stays here in /lib (vs. inlined in one component) because both
 * AddLiquidityPreview and LiquidityPage need to share the same
 * debounced inputs to hit the same React Query cache key — otherwise
 * the page-level useQuery and the component-level useQuery would
 * fire as two separate network calls.
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const handle = setTimeout(() => setDebounced(value), delayMs)
    return () => clearTimeout(handle)
  }, [value, delayMs])
  return debounced
}
