package com.sber.dlmm.fee.dto;

import java.util.List;
import java.util.UUID;

public record FeesSummaryResponse(
        UUID userId,
        List<PoolFeeSummary> byPool,
        long totalUnclaimedX,
        long totalUnclaimedY,
        // Combined cross-token roll-ups. The frontend FeeSummary contract
        // (Positions / Dashboard / Profile KPIs) reads totalEarnedX/Y,
        // totalClaimed, totalUnclaimed — none of which the response carried
        // before, so every fee KPI rendered "0 ₽" despite real accruals
        // (UI-test F-09). totalClaimed/totalUnclaimed sum both token legs
        // (smallest-unit proxy — same convention the admin dashboard uses
        // for TVL/fees; most pools are SRUB-quoted so it reads as ₽). The
        // precise per-token split stays in byPool / totalUnclaimedX/Y.
        long totalEarnedX,
        long totalEarnedY,
        long totalClaimed,
        long totalUnclaimed
) {}
