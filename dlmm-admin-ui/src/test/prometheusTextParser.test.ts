import { describe, it, expect } from 'vitest'
import { parsePrometheusText, filterByName } from '../lib/prometheusTextParser'

describe('parsePrometheusText (F-15)', () => {
  it('parses a counter with labels and a value', () => {
    const text = `# HELP dlmm_gateway_ratelimit_total Requests after per-tier rate-limit check
# TYPE dlmm_gateway_ratelimit_total counter
dlmm_gateway_ratelimit_total{outcome="allowed",tier="FREE"} 42.0`
    const samples = parsePrometheusText(text)
    expect(samples).toHaveLength(1)
    expect(samples[0].name).toBe('dlmm_gateway_ratelimit_total')
    expect(samples[0].labels).toEqual({ outcome: 'allowed', tier: 'FREE' })
    expect(samples[0].value).toBe(42)
  })

  it('parses a counter without labels', () => {
    const text = `up 1.0`
    const samples = parsePrometheusText(text)
    expect(samples).toHaveLength(1)
    expect(samples[0].name).toBe('up')
    expect(samples[0].labels).toEqual({})
    expect(samples[0].value).toBe(1)
  })

  it('skips comment lines + blank lines', () => {
    const text = `
# HELP foo bar
# TYPE foo counter

foo 1.0
foo 2.0
`
    const samples = parsePrometheusText(text)
    expect(samples).toHaveLength(2)
    expect(samples[0].value).toBe(1)
    expect(samples[1].value).toBe(2)
  })

  it('drops malformed lines silently', () => {
    const text = `
foo{broken value
bar{key="val"} not-a-number
baz{key="val"} 3.14
`
    const samples = parsePrometheusText(text)
    // Only the well-formed one survives.
    expect(samples).toEqual([{ name: 'baz', labels: { key: 'val' }, value: 3.14 }])
  })

  it('handles multiple samples of the same metric with different labels', () => {
    const text = `
dlmm_gateway_ratelimit_total{outcome="allowed",tier="FREE"} 10
dlmm_gateway_ratelimit_total{outcome="throttled",tier="FREE"} 2
dlmm_gateway_ratelimit_total{outcome="allowed",tier="PRO"} 100
dlmm_gateway_ratelimit_total{outcome="throttled",tier="PRO"} 5
`
    const samples = parsePrometheusText(text)
    const subset = filterByName(samples, 'dlmm_gateway_ratelimit_total')
    expect(subset).toHaveLength(4)
    const totalAllowed = subset.filter((s) => s.labels.outcome === 'allowed')
      .reduce((sum, s) => sum + s.value, 0)
    expect(totalAllowed).toBe(110)
  })

  it('handles label values with spaces (e.g. user agent style)', () => {
    const text = `req{ua="Mozilla 5.0",path="/api"} 7`
    const samples = parsePrometheusText(text)
    expect(samples[0].labels.ua).toBe('Mozilla 5.0')
    expect(samples[0].labels.path).toBe('/api')
  })

  it('ignores trailing-timestamp tokens (some exporters add them)', () => {
    const text = `foo{a="b"} 9 1234567890`
    const samples = parsePrometheusText(text)
    expect(samples[0].value).toBe(9)
  })
})
