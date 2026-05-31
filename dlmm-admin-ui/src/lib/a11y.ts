import type { KeyboardEvent } from 'react'

/**
 * a11y (WCAG 2.1.1 Keyboard / 4.1.2 Name-Role-Value): make a clickable
 * non-button element keyboard-operable. Spread onto the element alongside its
 * existing `onClick`; Enter/Space activate it and it exposes role + name.
 * Mirror of dlmm-user-ui/src/lib/a11y.ts.
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
