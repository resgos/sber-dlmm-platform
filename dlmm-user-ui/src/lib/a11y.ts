import type { KeyboardEvent } from 'react'

/**
 * a11y (WCAG 2.1.1 Keyboard / 4.1.2 Name-Role-Value): make a clickable
 * non-button element (a `<div>`/`<Space>` row, tile, etc.) keyboard-operable.
 * Spread onto the element ALONGSIDE its existing `onClick`. Enter/Space
 * activate it; it exposes `role="button"` + an accessible name to screen
 * readers. Mirrors the hand-rolled pattern already used in OrderBook.tsx.
 */
export function rowButtonProps(onActivate: () => void, label: string) {
  return {
    role: 'button',
    tabIndex: 0,
    'aria-label': label,
    onKeyDown: (e: KeyboardEvent) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault()
        onActivate()
      }
    },
  }
}
