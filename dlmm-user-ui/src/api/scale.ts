import type {
  TokenBalance,
  BinData,
  Pool,
  PoolDetail,
  Position,
  SwapQuote,
  Transaction,
  FeeSummary,
  FeeHistoryEntry,
  PreviewAddLiquidityResponse,
  OhlcvCandle,
} from './types'
import type { AutoClaimPolicyWire, AutoClaimLogEntry } from './fees'

/**
 * Sprint 16 (#14) — platform amount scale.
 *
 * The backend stores and transmits every token amount as a raw integer (`long`
 * / BIGINT) and never applies a token's `decimals`. We adopt a single uniform
 * platform scale: 1 raw unit = 10^-4 of a token (4 "platform decimals"). The
 * UI works in HUMAN token units; we divide raw→human on every response and
 * multiply human→raw on every request, here at the API boundary, so fractional
 * amounts (e.g. 0.5 SBTC) round-trip correctly instead of flooring to 0.
 *
 * Why a UNIFORM scale: a pool price is a Y/X ratio, so scaling both X and Y by
 * the same factor leaves prices, fee-bps, percentages and ratios UNCHANGED.
 * We therefore scale ONLY token *quantities* — never price/ratio/bps/% fields.
 * It also means every already-seeded whole-token value, after seed ×10^4 and
 * this ÷10^4, displays the SAME number it did before — fractional input is the
 * only new behaviour.
 *
 * Precision: human balances stay below 2^53 after scaling (exact in JS); only
 * large cosmetic aggregates (TVL / 24h volume) exceed it, where a sub-unit
 * rounding error is invisible in compact display. Backend stays in `long`
 * (max scaled value ~1.9e18, comfortably under 9.2e18).
 *
 * Token total/circulating supply is deliberately NOT scaled (never displayed
 * and unused in trade flows) — the seed migration leaves those columns alone
 * too, so the two sides stay consistent.
 */
export const AMOUNT_SCALE = 10_000

/** raw integer units → human token units (for display) */
export const fromRaw = (n: number): number => n / AMOUNT_SCALE

/** human token units → raw integer units (for sending). Round to kill FP dust. */
export const toRaw = (n: number): number => Math.round(n * AMOUNT_SCALE)

/** null-safe fromRaw — preserves null/undefined for optional amount fields. */
export const fromRawN = <T extends number | null | undefined>(n: T): T =>
  (n == null ? n : (n as number) / AMOUNT_SCALE) as T

// ── response scalers (raw → human) ───────────────────────────────────────

export const scaleBalance = (b: TokenBalance): TokenBalance => ({
  ...b,
  available: fromRaw(b.available),
  locked: fromRaw(b.locked),
  total: fromRaw(b.total),
})

/** bin reserves + liquidity are amounts; binId / price / compositionFactor are not. */
export const scaleBin = (b: BinData): BinData => ({
  ...b,
  liquidity: fromRaw(b.liquidity),
  reserveX: fromRaw(b.reserveX),
  reserveY: fromRaw(b.reserveY),
})

/** pool TVL + volume are amounts; bps / binStep / activeBinId / price / apy are not. */
export const scalePool = <T extends Pool>(p: T): T => ({
  ...p,
  totalTvlX: fromRaw(p.totalTvlX),
  totalTvlY: fromRaw(p.totalTvlY),
  volume24h: fromRaw(p.volume24h),
})

export const scalePoolDetail = (p: PoolDetail): PoolDetail => ({
  ...scalePool(p),
  totalFeesCollectedX: fromRaw(p.totalFeesCollectedX),
  totalFeesCollectedY: fromRaw(p.totalFeesCollectedY),
  bins: (p.bins || []).map(scaleBin),
})

export const scalePosition = (p: Position): Position => ({
  ...p,
  totalLiquidityShares: fromRaw(p.totalLiquidityShares),
  unclaimedFeeX: fromRaw(p.unclaimedFeeX),
  unclaimedFeeY: fromRaw(p.unclaimedFeeY),
  currentValueX: fromRaw(p.currentValueX),
  currentValueY: fromRaw(p.currentValueY),
  initialDepositX: fromRawN(p.initialDepositX),
  initialDepositY: fromRawN(p.initialDepositY),
})

/** amountIn / amountOut / fee are amounts; feeBps / binsCrossed / price / impact are not. */
export const scaleQuote = (q: SwapQuote): SwapQuote => ({
  ...q,
  amountIn: fromRaw(q.amountIn),
  amountOut: fromRaw(q.amountOut),
  fee: fromRaw(q.fee),
})

export const scaleTransaction = (t: Transaction): Transaction => ({
  ...t,
  amountIn: fromRawN(t.amountIn),
  amountOut: fromRawN(t.amountOut),
  feeAmount: fromRawN(t.feeAmount),
})

export const scaleFeeSummary = (f: FeeSummary): FeeSummary => ({
  ...f,
  totalEarnedX: fromRaw(f.totalEarnedX),
  totalEarnedY: fromRaw(f.totalEarnedY),
  totalClaimed: fromRaw(f.totalClaimed),
  totalUnclaimed: fromRaw(f.totalUnclaimed),
})

export const scaleFeeHistory = (e: FeeHistoryEntry): FeeHistoryEntry => ({
  ...e,
  amount: fromRaw(e.amount),
})

export const scalePreviewAddLiquidity = (
  r: PreviewAddLiquidityResponse,
): PreviewAddLiquidityResponse => ({
  ...r,
  tvlBeforeX: fromRaw(r.tvlBeforeX),
  tvlBeforeY: fromRaw(r.tvlBeforeY),
  tvlAfterX: fromRaw(r.tvlAfterX),
  tvlAfterY: fromRaw(r.tvlAfterY),
  depositedX: fromRaw(r.depositedX),
  depositedY: fromRaw(r.depositedY),
  estimatedFeesPerDayY: fromRaw(r.estimatedFeesPerDayY),
  binAllocations: (r.binAllocations || []).map((a) => ({
    ...a,
    amountX: fromRaw(a.amountX),
    amountY: fromRaw(a.amountY),
    liquidityShares: fromRaw(a.liquidityShares),
  })),
})

/** OHLCV: only `volume` is an amount; open/high/low/close are prices. */
export const scaleOhlcv = (c: OhlcvCandle): OhlcvCandle => ({
  ...c,
  volume: fromRaw(c.volume),
})

export const scaleAutoClaimPolicy = (p: AutoClaimPolicyWire): AutoClaimPolicyWire => ({
  ...p,
  thresholdAmount: fromRaw(p.thresholdAmount),
  dailyCap: fromRaw(p.dailyCap),
})

export const scaleAutoClaimLog = (e: AutoClaimLogEntry): AutoClaimLogEntry => ({
  ...e,
  amountX: fromRaw(e.amountX),
  amountY: fromRaw(e.amountY),
})
