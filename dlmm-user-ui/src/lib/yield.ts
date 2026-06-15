/**
 * Forward projection of LP fee income for the pool-page yield calculator:
 * what a deposit of `amountRub` would earn in fees over `days` at the pool's
 * current annualised `apyPct`, pro-rated linearly:
 *
 *   fees = amount · (apy / 100) · (days / 365)
 *
 * This is a CURRENT-APY estimate only — it assumes today's trading volume and
 * an in-range position; it does NOT model impermanent loss or APY drift (the
 * UI states that). Non-positive / non-finite inputs return 0 so the calculator
 * never shows NaN.
 *
 * @param amountRub deposit size in SRUB (human units)
 * @param apyPct    annualised APY as a whole-number percent (e.g. 12.5)
 * @param days      holding period in days
 * @returns projected fee income in SRUB
 */
export function projectFeeIncome(amountRub: number, apyPct: number, days: number): number {
  if (!(amountRub > 0) || !(apyPct > 0) || !(days > 0)) return 0
  return amountRub * (apyPct / 100) * (days / 365)
}

/**
 * Effective return over the period as a percent (apy pro-rated to `days`),
 * i.e. the {@link projectFeeIncome} expressed relative to the deposit. 0 for
 * non-positive inputs.
 */
export function periodReturnPct(apyPct: number, days: number): number {
  if (!(apyPct > 0) || !(days > 0)) return 0
  return (apyPct * days) / 365
}
