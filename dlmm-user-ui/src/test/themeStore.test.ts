import { describe, it, expect, beforeEach, vi } from 'vitest'
import { themeStore } from '../store/themeStore'

describe('themeStore (P2-15)', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    document.documentElement.style.colorScheme = ''
  })

  it('defaults to "system" when nothing is persisted', () => {
    expect(themeStore.getMode()).toBe('system')
  })

  it('setMode persists the preference and applies it to <html>', () => {
    themeStore.setMode('dark')
    expect(localStorage.getItem('dlmm.user.theme')).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(document.documentElement.style.colorScheme).toBe('dark')
  })

  it('setMode("light") flips both attribute and colorScheme', () => {
    themeStore.setMode('dark')
    themeStore.setMode('light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(document.documentElement.style.colorScheme).toBe('light')
  })

  it('subscribe + setMode notifies listeners synchronously', () => {
    const cb = vi.fn()
    const unsubscribe = themeStore.subscribe(cb)
    themeStore.setMode('dark')
    expect(cb).toHaveBeenCalledOnce()
    unsubscribe()
    themeStore.setMode('light')
    expect(cb).toHaveBeenCalledOnce()
  })

  it('getEffective resolves "system" against window.matchMedia', () => {
    themeStore.setMode('system')
    // jsdom default: matchMedia returns matches=false → 'light'.
    expect(themeStore.getEffective()).toBe('light')
  })

  it('initialize applies the persisted value', () => {
    localStorage.setItem('dlmm.user.theme', 'dark')
    themeStore.initialize()
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })
})
