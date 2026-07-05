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
/**
 * 2026-07-06 — the request-side half of the same contract. A day picked in
 * the UI is a LOCAL calendar day, but the backend compares zoneless
 * LocalDateTime bounds against a UTC ledger — sending
 * `2026-07-06T00:00:00` verbatim shifts the window by the viewer's offset
 * (Moscow's «сегодня» silently covered 21:00-yesterday…20:59-today UTC…
 * actually the reverse: it MISSED 21:00–23:59 UTC of the local evening).
 * Convert the local day bounds to their UTC instants and send those,
 * formatted the zoneless way the backend parses.
 */
function toZonelessUtc(localDateTime: string): string {
  // `new Date('YYYY-MM-DDTHH:mm:ss[.SSS]')` parses as LOCAL time by spec;
  // toISOString() re-expresses that instant in UTC. Strip the trailing Z —
  // the backend's LocalDateTime parser rejects zone designators.
  return new Date(localDateTime).toISOString().replace(/Z$/, '')
}

/** UTC instant (zoneless string) of the START of a local calendar day. */
export function utcStartOfLocalDay(date: string): string {
  return toZonelessUtc(`${date}T00:00:00`)
}

/** UTC instant (zoneless string) of the END of a local calendar day. */
export function utcEndOfLocalDay(date: string): string {
  return toZonelessUtc(`${date}T23:59:59.999`)
}

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
