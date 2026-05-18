import '@testing-library/jest-dom'

// Sprint 8 C-7 — vitest setup mirrors user-ui's so behaviour stays parallel.
// AntD touches both matchMedia and getComputedStyle during component mount;
// jsdom doesn't provide either by default, so we stub them.

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

const originalGetComputedStyle = window.getComputedStyle
window.getComputedStyle = (elt: Element, pseudoElt?: string | null) => {
  const style = originalGetComputedStyle(elt, pseudoElt)
  return style
}
