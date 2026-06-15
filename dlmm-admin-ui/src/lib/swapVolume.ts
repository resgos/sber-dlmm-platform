import type { Transaction } from '@/api/types'

/**
 * 24h swap turnover expressed in the QUOTE token (SRUB) — the platform's
 * canonical "volume" metric, mirroring the pool-engine `volume24h` accrual
 * (the SRUB leg of every swap).
 *
 * <p>For each SWAP take the SRUB side: {@code amountOut} when the user received
 * SRUB (a buy of the base), {@code amountIn} when they paid SRUB (a sell into
 * the base). Non-SRUB pairs (there are none in the demo catalogue — every pool
 * is X/SRUB) are SKIPPED rather than summed in a foreign unit: the bug behind
 * the meaningless "Объём свопов 24ч = 1" KPI was reducing raw `amountIn` across
 * mixed token units (0.05 SETH + 32k SUSDT + …). Fixed in admin commit
 * fbb999a; this extracts the rule into one tested place so it can't silently
 * regress (the platform metrics-contract idea, in the small).
 *
 * <p>Amounts must already be human-scaled (the API layer's `scaleTransaction`
 * applies the ×10⁴ platform scale before this runs).
 *
 * @param txs transactions to aggregate (any types; non-SWAP rows are ignored)
 * @returns total SRUB turnover across the SWAP rows
 */
export function swapVolumeSrub(txs: Transaction[]): number {
  return txs
    .filter((t) => t.txType === 'SWAP')
    .reduce((acc, t) => {
      if (t.tokenOutSymbol === 'SRUB') return acc + (t.amountOut ?? 0)
      if (t.tokenInSymbol === 'SRUB') return acc + (t.amountIn ?? 0)
      return acc
    }, 0)
}
