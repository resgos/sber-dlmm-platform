package com.sber.dlmm.pool.dto;

import java.math.BigDecimal;

public record BinResponse(
        int binId,
        BigDecimal price,
        long liquidity,
        long reserveX,
        long reserveY,
        BigDecimal compositionFactor
) {
}
