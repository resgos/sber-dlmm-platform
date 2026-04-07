package com.sber.dlmm.common.util;

public final class FeeCalculator {

    private FeeCalculator() {}

    public static long calculateSwapFee(long amountIn, int baseFeeBps, int volatilityAccumulator, int binStep) {
        long baseFee = amountIn * baseFeeBps / 10_000;
        long variableFee = (long) volatilityAccumulator * volatilityAccumulator * binStep / 10_000_000_000L;
        variableFee = amountIn * variableFee / 10_000;
        return baseFee + variableFee;
    }

    public static int updateVolatilityAccumulator(int currentVA, int binsCrossed, int maxVolatility) {
        int newVA = currentVA + Math.abs(binsCrossed);
        return Math.min(newVA, maxVolatility);
    }

    public static int decayVolatilityAccumulator(int currentVA, int decayRate) {
        int decayed = currentVA * (10_000 - decayRate) / 10_000;
        return Math.max(decayed, 0);
    }
}
