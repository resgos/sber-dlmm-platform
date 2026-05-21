package com.sber.dlmm.pool.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePoolRequest(
        @NotNull UUID tokenXId,
        @NotNull UUID tokenYId,
        @Min(1) @Max(500) int binStep,
        @Min(1) @Max(100) int baseFeeBps,
        @NotNull BigDecimal initialPrice,
        int maxVariableFeeBps,
        // Sprint 9-DS-r4 (P1-15) — cap mirrors the 3.A legal memo
        // ceiling. >5% requires broker-dealer reg re-evaluation; we
        // refuse the request rather than silently clamp. Lower bound
        // is 0 (opt-out is the safe default; admin turns it on
        // per-pool via /api/v1/pools/{id}/protocol-fee).
        @Min(0) @Max(5) int protocolFeePct,
        int decayPeriodSeconds
) {
    public CreatePoolRequest {
        if (maxVariableFeeBps <= 0) maxVariableFeeBps = 300;
        // Sprint 9-DS-r4 (P1-15) — silent bump from 0 → 20 dropped.
        // 0 is now a valid explicit opt-out and must be preserved.
        // Out-of-range values are rejected at the validation layer
        // above, not coerced.
        if (decayPeriodSeconds <= 0) decayPeriodSeconds = 600;
    }
}
