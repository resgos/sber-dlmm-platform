import { describe, it, expect } from 'vitest'
import { normalizeApiDates } from '../lib/apiDates'

// 2026-07-05 — UTC-shift fix. The backend serializes LocalDateTime zoneless
// («2026-07-05T19:57:32.892778», UTC by contract); browsers parse that as
// LOCAL time, so a fresh notification read «3 часа назад» in Moscow. The
// normalizer appends Z at the axios boundary.
describe('normalizeApiDates', () => {
  it('appends Z to zoneless datetimes (seconds / micros / nanos precision)', () => {
    expect(normalizeApiDates('2026-07-05T19:57:32')).toBe('2026-07-05T19:57:32Z')
    expect(normalizeApiDates('2026-07-05T19:57:32.892778')).toBe('2026-07-05T19:57:32.892778Z')
    expect(normalizeApiDates('2026-07-05T19:59:30.186833141')).toBe('2026-07-05T19:59:30.186833141Z')
  })

  it('leaves explicit-zone strings untouched', () => {
    expect(normalizeApiDates('2026-07-05T19:57:32Z')).toBe('2026-07-05T19:57:32Z')
    expect(normalizeApiDates('2026-07-05T19:57:32.100+03:00')).toBe('2026-07-05T19:57:32.100+03:00')
  })

  it('leaves date-only strings (calendar dates, not instants) untouched', () => {
    expect(normalizeApiDates('2026-07-05')).toBe('2026-07-05')
  })

  it('leaves non-date strings and non-strings untouched', () => {
    expect(normalizeApiDates('e1e0e8da-ac43-4a65-b491-9e1c17cdf888')).toBe('e1e0e8da-ac43-4a65-b491-9e1c17cdf888')
    expect(normalizeApiDates('SWAP_COMPLETED')).toBe('SWAP_COMPLETED')
    expect(normalizeApiDates(12345)).toBe(12345)
    expect(normalizeApiDates(null)).toBe(null)
    expect(normalizeApiDates(true)).toBe(true)
  })

  it('walks nested objects and arrays in place', () => {
    const payload = {
      content: [
        { id: 'a', createdAt: '2026-07-05T19:57:32.892778', amount: 10000 },
        { id: 'b', confirmedAt: null, meta: { closedAt: '2026-06-01T00:00:01' } },
      ],
      page: 0,
    }
    const out = normalizeApiDates(payload)
    expect(out).toBe(payload) // same reference — in-place
    expect(out.content[0]!.createdAt).toBe('2026-07-05T19:57:32.892778Z')
    expect(out.content[1]!.meta!.closedAt).toBe('2026-06-01T00:00:01Z')
    expect(out.content[0]!.amount).toBe(10000)
  })

  it('rendered instant is correct after normalization (the actual bug)', () => {
    // Zoneless «19:57:32 UTC» must become the same epoch instant as the
    // explicit-UTC string — not the viewer's local 19:57.
    const fixed = new Date(normalizeApiDates('2026-07-05T19:57:32') as string).getTime()
    expect(fixed).toBe(Date.parse('2026-07-05T19:57:32Z'))
  })
})
