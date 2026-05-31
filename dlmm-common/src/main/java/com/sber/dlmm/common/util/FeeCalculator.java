package com.sber.dlmm.common.util;

import java.math.BigInteger;

public final class FeeCalculator {

    private FeeCalculator() {}

    /**
     * Hard cap on the total swap-fee rate: 10% (1000 bps), mirroring Meteora's
     * {@code MAX_FEE_RATE}. Guards against the fee ever exceeding 10% of the input
     * (M-3, math audit) — important once the variable-fee term is made functional,
     * since an uncapped base+variable could exceed 100% and produce a negative net.
     */
    public static final int MAX_FEE_BPS = 1_000;

    private static final BigInteger BPS_DIVISOR = BigInteger.valueOf(10_000L);

    /**
     * Swap fee charged on the INPUT amount = base + volatility-variable, in bps,
     * capped at {@link #MAX_FEE_BPS}.
     *
     * <p><b>Overflow-safe (Sprint 16):</b> after the 1e-4 platform amount scale,
     * {@code amountIn} is a large raw integer (≈1e16 for a big swap) so
     * {@code amountIn * feeBps} overflows {@code long}. The multiply is done in
     * {@link BigInteger}. (Before the scale, {@code amountIn} was ≤~1e12 and the
     * old {@code amountIn * baseFeeBps / 10_000} fit; post-scale it silently
     * overflowed to a wrong/negative fee on large swaps.)
     *
     * <p><b>Known divergence from Meteora (deliberately deferred):</b> the variable
     * term keeps its legacy shape (<code>VA² · binStep / 1e10</code>), which is ≈0
     * for normal volatility — the faithful Meteora dynamic-fee port (binStep²,
     * a per-pool {@code variableFeeControl}, 1e9 rate precision, and a reference-frame
     * volatility accumulator) is a separate, economics-reviewed backlog item. See the
     * math-overhaul note in {@code docs/BACKLOG} and the {@code @Disabled volatileMarket}
     * test. This method's contract (base fee + cap + overflow safety) is unchanged by
     * that future work.
     */
    public static long calculateSwapFee(long amountIn, int baseFeeBps, int volatilityAccumulator, int binStep) {
        if (amountIn <= 0) return 0;
        long feeBps = totalFeeBps(baseFeeBps, volatilityAccumulator, binStep);
        return BigInteger.valueOf(amountIn)
                .multiply(BigInteger.valueOf(feeBps))
                .divide(BPS_DIVISOR)
                .longValueExact();
    }

    /**
     * Total fee rate in bps = base + variable, capped at {@link #MAX_FEE_BPS} (M-3).
     * Shared by {@code calculateSwapFee} and the pool's displayed "current dynamic
     * fee" so the charged fee and the shown fee can never diverge.
     */
    public static int totalFeeBps(int baseFeeBps, int volatilityAccumulator, int binStep) {
        long variableFeeBps = (long) volatilityAccumulator * volatilityAccumulator * binStep / 10_000_000_000L;
        long total = (long) baseFeeBps + variableFeeBps;
        return (int) Math.min(total, MAX_FEE_BPS);
    }

    public static int updateVolatilityAccumulator(int currentVA, int binsCrossed, int maxVolatility) {
        int newVA = currentVA + Math.abs(binsCrossed);
        return Math.min(newVA, maxVolatility);
    }

    public static int decayVolatilityAccumulator(int currentVA, int decayRate) {
        // C-3 (math audit): clamp the decay rate to [0, 10000]. The scheduler computes
        // decayRate = 10000*60/decayPeriodSeconds, which EXCEEDS 10000 whenever
        // decayPeriodSeconds < 60 → (10000 - decayRate) goes negative → VA·negative →
        // VA wrongly slammed to 0 every tick. Clamping makes a short decay period
        // decay fast-but-correctly instead of destroying the volatility state.
        int r = Math.max(0, Math.min(decayRate, 10_000));
        return Math.max(currentVA * (10_000 - r) / 10_000, 0);
    }
}
