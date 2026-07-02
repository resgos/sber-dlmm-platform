import type {
  Pool,
  PoolDetail,
  BinData,
  Transaction,
  SuspiciousTransaction,
  DashboardData,
  PoolAnalytics,
  TokenAnalytics,
} from './types'
import type { OtcBlockTrade } from './otc'

/**
 * Sprint 16 (#14) — uniform platform amount scale (admin-ui side).
 *
 * The backend stores every token amount as a raw integer where 1 unit = 10^-4
 * of a token; it never applies a token's `decimals`. The shared DB was rescaled
 * ×10^4 (docker/11-seed-rescale-amounts-1e4.sql) so the user-ui can offer
 * fractional amounts. This module applies the matching ÷10^4 (responses) /
 * ×10^4 (requests) at the admin-ui API boundary so operator dashboards keep
 * showing human-scale numbers instead of values 10000x too large.
 *
 * A UNIFORM scale leaves prices (Y/X ratios) unchanged — scale ONLY token
 * quantities, never price/bps/%/ratios/ids/counts. Token total/circulating/max
 * supply is left unscaled on both sides (undisplayed, unused in trade flows).
 * Mirrors dlmm-user-ui/src/api/scale.ts.
 */
export const AMOUNT_SCALE = 10_000

/** raw integer units → human token units (display) */
export const fromRaw = (n: number): number => n / AMOUNT_SCALE
/** human token units → raw integer units (send). Round to kill FP dust. */
export const toRaw = (n: number): number => Math.round(n * AMOUNT_SCALE)
/** null-safe fromRaw — preserves null/undefined for optional amount fields. */
export const fromRawN = <T extends number | null | undefined>(n: T): T =>
  (n == null ? n : (n as number) / AMOUNT_SCALE) as T

export const scaleBin = (b: BinData): BinData => ({
  ...b,
  liquidity: fromRaw(b.liquidity),
  reserveX: fromRaw(b.reserveX),
  reserveY: fromRaw(b.reserveY),
})

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

export const scaleTransaction = (t: Transaction): Transaction => ({
  ...t,
  amountIn: fromRawN(t.amountIn),
  amountOut: fromRawN(t.amountOut),
  feeAmount: fromRawN(t.feeAmount),
})

export const scaleSuspiciousTransaction = (t: SuspiciousTransaction): SuspiciousTransaction => ({
  ...t,
  // admin-bff returns the flag's transaction id as `transactionId`, not `id`
  // (the FE type/columns/rowKey expect `id`) — map it so the ID column, CSV and
  // React key resolve instead of rendering blank. The amount is raw (×10⁴).
  id: t.id ?? (t as { transactionId?: string }).transactionId ?? '',
  // 2026-06-17 — same drift, second field: the wire now carries `txType`
  // (added to the BFF DTO), the FE reads `type`; map it so the «Тип» column
  // and CSV stop rendering blank.
  type: t.type ?? (t as { txType?: string }).txType ?? '',
  amount: fromRaw(t.amount),
})

/** Only the *Rub amount fields scale; totalUsers / activePools / ... are counts. */
export const scaleDashboard = (d: DashboardData): DashboardData => ({
  ...d,
  totalTvlRub: fromRaw(d.totalTvlRub),
  volume24hRub: fromRaw(d.volume24hRub),
  totalFeesCollectedRub: fromRaw(d.totalFeesCollectedRub),
})

/** tvl / volume / fees series scale; apy series is a ratio — untouched. */
export const scalePoolAnalytics = (a: PoolAnalytics): PoolAnalytics => ({
  ...a,
  tvlHistory: (a.tvlHistory || []).map((e) => ({ ...e, tvl: fromRaw(e.tvl) })),
  volumeHistory: (a.volumeHistory || []).map((e) => ({ ...e, volume: fromRaw(e.volume) })),
  feeHistory: (a.feeHistory || []).map((e) => ({ ...e, fees: fromRaw(e.fees) })),
})

/** volume series scales; price + supply series are untouched (price ratio; supply unscaled). */
export const scaleTokenAnalytics = (a: TokenAnalytics): TokenAnalytics => ({
  ...a,
  volumeHistory: (a.volumeHistory || []).map((e) => ({ ...e, volume: fromRaw(e.volume) })),
})

/** amountIn/amountOut scale; quotedPriceMicro is a price — untouched. */
export const scaleOtcBlockTrade = (t: OtcBlockTrade): OtcBlockTrade => ({
  ...t,
  amountIn: fromRaw(t.amountIn),
  amountOut: fromRawN(t.amountOut),
})
