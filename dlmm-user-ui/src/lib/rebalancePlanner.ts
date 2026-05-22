// Sprint 10 F-07 — Portfolio rebalancer planner.
//
// Pure-logic module. Given a current portfolio (token balances) and a
// target allocation in percent, computes the minimum set of swaps that
// brings the actual allocation within tolerance of the target.
//
// Design choices:
//   1. SRUB is the pivot currency. Every swap routes XXX → SRUB → YYY
//      because we don't have multi-hop routing in pool-engine yet (F-12
//      Sprint 11). For pair X↔Y where one side is SRUB the route is
//      one hop. The plan emits hops, not pairs.
//   2. Tolerance: skip a swap if the resulting % is within ±0.5% of
//      target. Avoids 100-ruble dust swaps that incur 30 bps fee.
//   3. Greedy: order sells largest-overshoot first so the SRUB pivot
//      pool is funded before we hit any buys. No multi-pass needed at
//      the size we operate on.
//
// Not handled (deferred to Sprint 11):
//   - Multi-hop routing for non-SRUB pivot pairs
//   - Cross-pool slippage budgeting
//   - Atomic batch execution (today: sequential, stop-on-first-failure)
//
// Unit tests live in src/test/rebalancePlanner.test.ts.

export interface PortfolioTokenView {
  /** Token id (UUID). */
  tokenId: string
  /** Display symbol, e.g. "SRUB". */
  symbol: string
  /** Available + locked, in token units. */
  amount: number
  /** ₽-equivalent price. SRUB = 1, others priced via SRUB-anchored pools. */
  priceRub: number
}

export interface TargetAllocation {
  symbol: string
  /** Target percentage of total ₽ portfolio (0-100). */
  targetPct: number
}

export interface RebalanceHop {
  /** Symbol to sell. */
  fromSymbol: string
  /** Symbol to buy. */
  toSymbol: string
  /** Amount of `fromSymbol` to sell, in token units. */
  amountIn: number
  /** Approximate ₽ value of the swap (for UX display). */
  approxRubValue: number
  /** Free-form reason, e.g. "GAZP overweight by 4.2%". */
  reason: string
}

export interface RebalancePlan {
  hops: RebalanceHop[]
  /** Total ₽ moved through the rebalance (informational). */
  totalRubMoved: number
  /** Symbols already in tolerance — included for UX completeness. */
  skipped: Array<{ symbol: string; reason: string }>
}

const TOLERANCE_PCT = 0.5
const PIVOT_SYMBOL = 'SRUB'

/**
 * Compute the swap plan that brings `portfolio` toward `targets`.
 *
 * Returns hops in execution order: sells first (overshoot), then buys
 * (undershoot), pivoting via SRUB. A hop with `fromSymbol === toSymbol`
 * is never emitted.
 */
export function planRebalance(
  portfolio: PortfolioTokenView[],
  targets: TargetAllocation[],
): RebalancePlan {
  const totalRub = portfolio.reduce((s, p) => s + p.amount * p.priceRub, 0)
  if (totalRub <= 0) {
    return { hops: [], totalRubMoved: 0, skipped: [] }
  }

  // Build a per-symbol view: current %, target %, delta (positive = overweight).
  const portfolioBySymbol = new Map(portfolio.map((p) => [p.symbol, p]))
  const targetBySymbol = new Map(targets.map((t) => [t.symbol, t.targetPct]))

  interface Row {
    symbol: string
    tokenId: string
    priceRub: number
    currentRub: number
    currentPct: number
    targetPct: number
    deltaPct: number
    deltaRub: number
  }

  const rows: Row[] = []
  // Iterate over the union of symbols so a target on a token the user
  // doesn't yet hold (0-balance) still triggers a buy plan.
  const allSymbols = new Set([...portfolioBySymbol.keys(), ...targetBySymbol.keys()])
  for (const symbol of allSymbols) {
    const p = portfolioBySymbol.get(symbol)
    const currentRub = p ? p.amount * p.priceRub : 0
    const currentPct = (currentRub / totalRub) * 100
    const targetPct = targetBySymbol.get(symbol) ?? 0
    rows.push({
      symbol,
      tokenId: p?.tokenId ?? '',
      priceRub: p?.priceRub ?? 0,
      currentRub,
      currentPct,
      targetPct,
      deltaPct: currentPct - targetPct,
      deltaRub: ((currentPct - targetPct) / 100) * totalRub,
    })
  }

  const hops: RebalanceHop[] = []
  const skipped: Array<{ symbol: string; reason: string }> = []
  let totalRubMoved = 0

  // Sort: largest overshoot first (sell side); within each side, largest
  // delta first so the SRUB pivot pool fills before any buy fires.
  rows.sort((a, b) => b.deltaPct - a.deltaPct)

  // SELLS — every non-SRUB symbol with deltaPct > +tolerance.
  for (const r of rows) {
    if (Math.abs(r.deltaPct) <= TOLERANCE_PCT) {
      skipped.push({
        symbol: r.symbol,
        reason: `в пределах допуска ±${TOLERANCE_PCT}% (текущая ${r.currentPct.toFixed(1)}%, цель ${r.targetPct.toFixed(1)}%)`,
      })
      continue
    }
    if (r.deltaPct <= 0) continue // undershoot — handled in BUY pass.
    if (r.symbol === PIVOT_SYMBOL) continue // pivot — auto-balances.
    if (r.priceRub <= 0) {
      skipped.push({ symbol: r.symbol, reason: 'нет цены в ₽ — пропуск' })
      continue
    }

    const amountIn = Math.floor(r.deltaRub / r.priceRub)
    if (amountIn <= 0) continue
    hops.push({
      fromSymbol: r.symbol,
      toSymbol: PIVOT_SYMBOL,
      amountIn,
      approxRubValue: amountIn * r.priceRub,
      reason: `Перевес ${r.symbol} на ${r.deltaPct.toFixed(1)}%`,
    })
    totalRubMoved += amountIn * r.priceRub
  }

  // BUYS — every non-SRUB symbol with deltaPct < -tolerance, funded from pivot.
  // Re-sort by undershoot magnitude so the largest gap fills first.
  rows.sort((a, b) => a.deltaPct - b.deltaPct)
  for (const r of rows) {
    if (Math.abs(r.deltaPct) <= TOLERANCE_PCT) continue
    if (r.deltaPct >= 0) continue
    if (r.symbol === PIVOT_SYMBOL) continue
    if (r.priceRub <= 0) {
      skipped.push({ symbol: r.symbol, reason: 'нет цены в ₽ — пропуск покупки' })
      continue
    }

    const buyRub = -r.deltaRub // deltaRub negative for undershoot
    const amountIn = Math.floor(buyRub)
    if (amountIn <= 0) continue
    hops.push({
      fromSymbol: PIVOT_SYMBOL,
      toSymbol: r.symbol,
      amountIn,
      approxRubValue: amountIn,
      reason: `Недовес ${r.symbol} на ${(-r.deltaPct).toFixed(1)}%`,
    })
    totalRubMoved += amountIn
  }

  return { hops, totalRubMoved, skipped }
}

/**
 * Sum of targets must be 100% (within rounding tolerance) for a valid
 * rebalance. Returns null if valid, an error message otherwise.
 */
export function validateTargets(targets: TargetAllocation[]): string | null {
  const sum = targets.reduce((s, t) => s + t.targetPct, 0)
  if (Math.abs(sum - 100) > 0.1) {
    return `Сумма целевых долей = ${sum.toFixed(1)}%, должна быть 100%`
  }
  if (targets.some((t) => t.targetPct < 0)) {
    return 'Доля не может быть отрицательной'
  }
  if (targets.some((t) => t.targetPct > 100)) {
    return 'Доля не может превышать 100%'
  }
  return null
}
