import { describe, it, expect } from 'vitest'
import ru from '../i18n/locales/ru.json'
import en from '../i18n/locales/en.json'

/**
 * Sprint 9-DS-r4 P2-14 — guards against translation drift.
 *
 * Both bundles must expose the same key tree, otherwise a switch to
 * EN crashes the page with "translation key undefined". This test
 * walks both objects and asserts the set of leaf keys matches.
 */

type Bundle = Record<string, unknown>

// i18next plural-form suffixes: ru uses _one/_few/_many; en uses _one/_other.
// Strip them when comparing keys so the parity check measures *concepts*
// (e.g. "dashboard.activePositions"), not per-language plural slots.
const PLURAL_SUFFIXES = ['_zero', '_one', '_two', '_few', '_many', '_other']
function normaliseKey(k: string): string {
  for (const suf of PLURAL_SUFFIXES) {
    if (k.endsWith(suf)) return k.slice(0, -suf.length)
  }
  return k
}

function collectKeys(obj: Bundle, prefix = '', out: Set<string> = new Set()): Set<string> {
  for (const [k, v] of Object.entries(obj)) {
    if (k === '_comment') continue
    const full = prefix ? `${prefix}.${k}` : k
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      collectKeys(v as Bundle, full, out)
    } else {
      out.add(normaliseKey(full))
    }
  }
  return out
}

describe('i18n bundle parity (P2-14)', () => {
  const ruKeys = collectKeys(ru as Bundle)
  const enKeys = collectKeys(en as Bundle)

  it('ru bundle has at least 50 strings (sanity floor)', () => {
    expect(ruKeys.size).toBeGreaterThanOrEqual(50)
  })

  it('en bundle has the same conceptual coverage as ru (plurals normalised)', () => {
    expect(enKeys.size).toBe(ruKeys.size)
  })

  it('every ru key has an en counterpart', () => {
    const missing = [...ruKeys].filter((k) => !enKeys.has(k))
    expect(missing, `Missing in en.json: ${missing.join(', ')}`).toHaveLength(0)
  })

  it('every en key has a ru counterpart (catches typos in en.json)', () => {
    const extra = [...enKeys].filter((k) => !ruKeys.has(k))
    expect(extra, `Extra in en.json (no ru): ${extra.join(', ')}`).toHaveLength(0)
  })
})
