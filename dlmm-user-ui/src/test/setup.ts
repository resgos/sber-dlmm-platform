import '@testing-library/jest-dom'
// Sprint 8 C-4 — initialise i18next so components calling useTranslation()
// in tests get real RU strings (not key-fallback like "swap.title"). Tests
// asserting on visible Russian text (e.g. SwapPage's "Обмен" headline)
// would break without this side-effect import.
import '../i18n'

// Mock window.matchMedia for Ant Design components
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
})

// Mock window.getComputedStyle for Ant Design
const originalGetComputedStyle = window.getComputedStyle
window.getComputedStyle = (elt: Element, pseudoElt?: string | null) => {
  const style = originalGetComputedStyle(elt, pseudoElt)
  return style
}
