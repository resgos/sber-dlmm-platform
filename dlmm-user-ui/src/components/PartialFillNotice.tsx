import { Alert } from 'antd'
import type { CSSProperties } from 'react'
import { formatTokenAmount } from '@/lib/format'

/**
 * A market swap is a PARTIAL FILL when the pool can't absorb the whole requested
 * input: the backend quote then reports {@code amountIn} (the fillable / consumed
 * amount) strictly below what the user typed, and the rest simply doesn't fit into
 * the available bins. The 0.1% tolerance absorbs integer-rounding noise so a fully
 * fillable swap isn't flagged.
 */
export function isPartialFill(
  consumed: number | null | undefined,
  requested: number | null | undefined,
): boolean {
  return (
    consumed != null &&
    requested != null &&
    requested > 0 &&
    consumed < requested * 0.999
  )
}

/**
 * Honest limited-liquidity warning for the swap form. When the requested amount
 * exceeds what the pool can fill, only {@code fillable} is actually swapped (and
 * the quote's price impact reflects only that slice — without this notice the form
 * looks like it would swap the full amount at an artificially low impact). Renders
 * nothing when the swap fills completely.
 */
export default function PartialFillNotice({
  fillable,
  requested,
  symbol,
  style,
}: {
  fillable: number | null | undefined
  requested: number | null | undefined
  symbol?: string
  style?: CSSProperties
}) {
  if (!isPartialFill(fillable, requested)) return null
  return (
    <Alert
      message="Ограниченная ликвидность пула"
      description={
        `В пул вмещается только ${formatTokenAmount(fillable!, symbol, { compact: true })} ` +
        `из ${formatTokenAmount(requested!, symbol, { compact: true })} — обменяется эта часть, ` +
        `остальное не войдёт. «Влияние на цену» показано для заполняемого объёма.`
      }
      type="warning"
      showIcon
      style={{ marginBottom: 12, borderRadius: 'var(--radius-sm)', ...style }}
    />
  )
}
