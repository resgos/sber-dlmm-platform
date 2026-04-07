package com.sber.dlmm.pool.dto;

public record BinAllocation(
        int binId,
        long amountX,
        long amountY,
        long liquidityShares
) {
}
