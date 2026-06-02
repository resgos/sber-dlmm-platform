import { describe, it, expect } from 'vitest'
import { isPartialFill } from '@/components/PartialFillNotice'

/**
 * Pins the partial-fill detection used by the swap forms: a swap is "partial"
 * when the pool can only absorb part of the requested input, so the backend
 * quote reports a consumed amountIn strictly (beyond rounding) below the typed
 * amount. This drives the "ограниченная ликвидность" notice + the CTA label.
 */
describe('isPartialFill', () => {
  it('flags when the consumed amount is well below the requested', () => {
    // e.g. typed 44.4M SETH but the pool can only fill ~1.23M
    expect(isPartialFill(1_230_797, 44_444_440)).toBe(true)
  })

  it('does not flag a full fill', () => {
    expect(isPartialFill(44_444_440, 44_444_440)).toBe(false)
  })

  it('tolerates sub-0.1% rounding noise (not a real partial fill)', () => {
    expect(isPartialFill(99.95, 100)).toBe(false) // 0.05% short — rounding
    expect(isPartialFill(99.8, 100)).toBe(true) // 0.2% short — real partial
  })

  it('returns false for null / undefined / non-positive inputs', () => {
    expect(isPartialFill(null, 100)).toBe(false)
    expect(isPartialFill(50, null)).toBe(false)
    expect(isPartialFill(undefined, undefined)).toBe(false)
    expect(isPartialFill(0, 0)).toBe(false)
  })
})
