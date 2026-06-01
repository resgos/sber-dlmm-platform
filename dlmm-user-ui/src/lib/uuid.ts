/**
 * RFC-4122 v4 UUID generator that also works in INSECURE browser contexts.
 *
 * Why this exists: `crypto.randomUUID()` is exposed ONLY in secure contexts
 * (HTTPS, or http://localhost / 127.0.0.1). When the user-ui is served from a
 * bare-IP HTTP origin — e.g. a test box at `http://141.105.65.29:3001` —
 * `crypto.randomUUID` is `undefined`, so calling it threw a `TypeError`
 * *before the HTTP request was even built*. That silently broke EVERY
 * idempotency-keyed mutation (swap, add/remove liquidity, claim, zap,
 * limit order, hedge, rebalance, СберСпасибо convert) with a generic
 * "operation failed" toast, while read-only calls (quotes) kept working and
 * nothing reached the backend. It was invisible on localhost (a secure
 * context, where randomUUID works).
 *
 * `crypto.getRandomValues()` IS available in insecure contexts, so we derive a
 * proper v4 UUID from it and only fall back to `Math.random()` if even that is
 * missing (should never happen in a browser).
 */
export function uuid(): string {
  const c = typeof crypto !== 'undefined' ? crypto : undefined
  if (c && typeof c.randomUUID === 'function') {
    return c.randomUUID()
  }
  const bytes = new Uint8Array(16)
  if (c && typeof c.getRandomValues === 'function') {
    c.getRandomValues(bytes)
  } else {
    for (let i = 0; i < 16; i++) bytes[i] = (Math.random() * 256) | 0
  }
  // Set the version (4) and variant (10xx) bits per RFC 4122 §4.4.
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  let out = ''
  for (let i = 0; i < 16; i++) {
    out += bytes[i].toString(16).padStart(2, '0')
    if (i === 3 || i === 5 || i === 7 || i === 9) out += '-'
  }
  return out
}
