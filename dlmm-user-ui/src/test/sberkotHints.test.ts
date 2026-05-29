import { describe, it, expect } from 'vitest'
import { resolveHint, HINTS, DEFAULT_HINT, OFF_KEY, seenKey } from '../components/sberkot/hints'

describe('Сберкот hint resolution', () => {
  it('maps each top-level route to its hint', () => {
    expect(resolveHint('/').key).toBe('home')
    expect(resolveHint('').key).toBe('home')
    expect(resolveHint('/swap').key).toBe('swap')
    expect(resolveHint('/pools').key).toBe('pools')
    expect(resolveHint('/positions').key).toBe('positions')
    expect(resolveHint('/hedge').key).toBe('hedge')
    expect(resolveHint('/profile').key).toBe('profile')
  })

  it('matches by prefix for nested non-pool routes', () => {
    expect(resolveHint('/swap?from=SRUB').key).toBe('swap')
    expect(resolveHint('/profile/security').key).toBe('profile')
    expect(resolveHint('/positions/anything').key).toBe('positions')
  })

  it('honours longest-prefix precedence for pool routes', () => {
    // /pools  <  /pools/<id>  <  /pools/<id>/liquidity — the MOST specific wins.
    expect(resolveHint('/pools').key).toBe('pools')
    expect(resolveHint('/pools/c0000000-0000-0000-0000-000000000110').key).toBe('poolDetail')
    expect(resolveHint('/pools/c0000000-0000-0000-0000-000000000110/liquidity').key).toBe('liquidity')
  })

  it('falls back to the default hint for unknown routes', () => {
    expect(resolveHint('/totally-unknown').key).toBe(DEFAULT_HINT.key)
    expect(resolveHint('/reviews').key).toBe(DEFAULT_HINT.key) // no dedicated hint → default
  })

  it('every hint has a non-empty title, text and a valid pose', () => {
    const poses = new Set(['greet', 'point', 'idle', 'celebrate'])
    for (const h of [...HINTS, DEFAULT_HINT]) {
      expect(h.title.length).toBeGreaterThan(0)
      expect(h.text.length).toBeGreaterThan(0)
      expect(poses.has(h.pose)).toBe(true)
    }
  })

  it('hint keys are unique (no two routes share a persistence key)', () => {
    const keys = [...HINTS.map((h) => h.key), DEFAULT_HINT.key]
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('exposes stable localStorage keys', () => {
    expect(OFF_KEY).toBe('sberkot:off')
    expect(seenKey('swap')).toBe('sberkot:seen:swap')
    expect(seenKey('home')).toBe('sberkot:seen:home')
  })
})
