package com.sber.dlmm.fee.dto;

import java.util.List;
import java.util.UUID;

public record FeesSummaryResponse(
        UUID userId,
        List<PoolFeeSummary> byPool,
        long totalUnclaimedX,
        long totalUnclaimedY
) {}
