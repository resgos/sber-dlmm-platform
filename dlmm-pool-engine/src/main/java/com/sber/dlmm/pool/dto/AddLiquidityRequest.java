package com.sber.dlmm.pool.dto;

import com.sber.dlmm.common.enums.LiquidityStrategy;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddLiquidityRequest(
        @NotNull UUID poolId,
        // Sprint 16 (Meteora parity) — @Min(0) (was @Min(1)) enables SINGLE-SIDED
        // liquidity: deposit only X or only Y (the other side = 0). The service
        // rejects both-zero. The bin distribution already routes X to bins ≥ active
        // and Y to bins ≤ active, so a one-sided deposit lands on the correct side.
        @Min(0) long amountX,
        @Min(0) long amountY,
        int binRangeMin,
        int binRangeMax,
        @NotNull LiquidityStrategy strategy,
        String idempotencyKey
) {
}
