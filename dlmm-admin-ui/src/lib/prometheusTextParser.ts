// Sprint 10 F-15 — minimal Prometheus text-exposition-format parser.
//
// `/actuator/prometheus` returns text/plain in the OpenMetrics format:
//
//   # HELP dlmm_gateway_ratelimit_total Requests after per-tier rate-limit check
//   # TYPE dlmm_gateway_ratelimit_total counter
//   dlmm_gateway_ratelimit_total{outcome="allowed",tier="FREE"} 0.0
//   dlmm_gateway_ratelimit_total{outcome="throttled",tier="FREE"} 0.0
//   ...
//
// We don't need a full parser — just enough to extract counter samples
// with their labels for the metrics we care about. Pulling in a real
// dep (prom-client / proper parser) would be overkill.
//
// Pure function; no I/O. Tested in isolation.

export interface Sample {
  /** Metric name, e.g. "dlmm_gateway_ratelimit_total". */
  name: string
  /** Labels parsed from the {} block. */
  labels: Record<string, string>
  /** Numeric value (NaN-safe). */
  value: number
}

/**
 * Parse the text body into a flat array of samples. Skips
 * `# HELP` / `# TYPE` lines and blank lines. Tolerant of unfamiliar
 * label syntax — silently drops malformed lines rather than throwing.
 */
export function parsePrometheusText(text: string): Sample[] {
  const out: Sample[] = []
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue

    // `name{labels} value` OR `name value` (no labels).
    const open = line.indexOf('{')
    let name: string
    let labels: Record<string, string> = {}
    let tail: string
    if (open === -1) {
      const sp = line.indexOf(' ')
      if (sp === -1) continue
      name = line.slice(0, sp)
      tail = line.slice(sp + 1).trim()
    } else {
      const close = line.indexOf('}', open)
      if (close === -1) continue
      name = line.slice(0, open)
      const labelBody = line.slice(open + 1, close)
      labels = parseLabels(labelBody)
      tail = line.slice(close + 1).trim()
    }
    // Value is the first whitespace-separated token of tail. Some
    // exporters append a timestamp; we ignore it.
    const valueTok = tail.split(/\s+/)[0]
    const value = Number(valueTok)
    if (!Number.isFinite(value)) continue
    out.push({ name, labels, value })
  }
  return out
}

function parseLabels(body: string): Record<string, string> {
  const labels: Record<string, string> = {}
  // Simple state machine — labels are `key="value"` comma-separated.
  // Backslash-escaped quotes inside values are rare in our metrics
  // surface; we keep the parser dumb and bail on the first quote.
  let i = 0
  while (i < body.length) {
    // Skip whitespace.
    while (i < body.length && body[i] === ' ') i++
    // Key — letters/digits/_/.
    const keyStart = i
    while (i < body.length && /[A-Za-z0-9_.]/.test(body[i])) i++
    if (i === keyStart) break
    const key = body.slice(keyStart, i)
    if (body[i] !== '=') break
    i++ // skip =
    if (body[i] !== '"') break
    i++ // skip opening "
    const valStart = i
    while (i < body.length && body[i] !== '"') i++
    const value = body.slice(valStart, i)
    labels[key] = value
    i++ // skip closing "
    if (body[i] === ',') i++
  }
  return labels
}

/** Filter helper — return only samples whose name matches. */
export function filterByName(samples: Sample[], name: string): Sample[] {
  return samples.filter((s) => s.name === name)
}
