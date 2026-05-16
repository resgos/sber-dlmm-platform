package com.sber.dlmm.common.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public final class BinMath {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

    private BinMath() {}

    public static BigDecimal binPrice(BigDecimal basePrice, int binStep, int binId) {
        BigDecimal factor = BigDecimal.ONE.add(new BigDecimal(binStep).divide(TEN_THOUSAND, MC));
        // Previously a naive O(N) loop. With seed pools using activeBinId =
        // 8_388_608 (2^23) that was 8.3M BigDecimal multiplies per pool —
        // ~700ms each. /api/v1/pools on 22 pools took ~17s and stalled the
        // admin dashboard's WebClient call. BigDecimal.pow(int) is O(log n)
        // via square-and-multiply, dropping the per-pool cost to <1ms.
        if (binId == 0) {
            return basePrice.setScale(18, RoundingMode.HALF_UP);
        }
        BigDecimal factorToBinId = factor.pow(Math.abs(binId), MC);
        BigDecimal price = binId > 0
                ? basePrice.multiply(factorToBinId, MC)
                : basePrice.divide(factorToBinId, MC);
        return price.setScale(18, RoundingMode.HALF_UP);
    }

    public static int priceToBinId(BigDecimal basePrice, int binStep, BigDecimal price) {
        if (basePrice.compareTo(BigDecimal.ZERO) <= 0 || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Prices must be positive");
        }
        double logRatio = Math.log(price.doubleValue() / basePrice.doubleValue());
        double logBase = Math.log(1.0 + binStep / 10_000.0);
        return (int) Math.round(logRatio / logBase);
    }

    public static BigDecimal compositionFactor(long reserveY, long liquidity) {
        if (liquidity == 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(reserveY).divide(new BigDecimal(liquidity), MC);
    }
}
