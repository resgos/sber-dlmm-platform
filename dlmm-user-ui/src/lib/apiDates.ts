/**
 * 2026-07-05 — UTC normalization for API timestamps.
 *
 * The backend serializes LocalDateTime WITHOUT a zone designator
 * (`2026-07-05T19:57:32.892778`), and those instants are UTC by contract.
 * Browsers (and dayjs) parse zoneless ISO strings as LOCAL time, silently
 * shifting every rendered timestamp by the viewer's UTC offset — a freshly
 * created notification read «3 часа назад» in Moscow the moment it arrived.
 *
 * Instead of chasing every `dayjs(...)`/`new Date(...)` call site, normalize
 * once at the axios boundary: append `Z` to zoneless datetime strings so every
 * downstream parse gets the correct instant. Strings that already carry
 * `Z`/`±hh:mm`, date-only values (`2026-07-05` — calendar dates, not
 * instants), and non-date strings are left untouched.
 *
 * Kept byte-identical in both UIs (see scripts/check-ui-shared-drift.mjs).
 */

// yyyy-MM-ddTHH:mm:ss with optional .fraction (JVM prints up to 9 digits),
// anchored — no trailing zone designator allowed.
const ZONELESS_DATETIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?$/

/**
 * Recursively walk a decoded JSON payload, rewriting zoneless datetime strings
 * to explicit-UTC (`…Z`). Mutates arrays/objects in place (the payload is
 * fresh from axios and owned by the caller); returns the same reference.
 */
export function normalizeApiDates<T>(value: T): T {
  if (typeof value === 'string') {
    return (ZONELESS_DATETIME.test(value) ? `${value}Z` : value) as unknown as T
  }
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) value[i] = normalizeApiDates(value[i])
    return value
  }
  if (value !== null && typeof value === 'object') {
    const rec = value as Record<string, unknown>
    for (const k of Object.keys(rec)) rec[k] = normalizeApiDates(rec[k])
    return value
  }
  return value
}
