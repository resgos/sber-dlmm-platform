package com.sber.dlmm.fee.dto;

import java.util.UUID;

public record ClaimFeesResponse(
        UUID positionId,
        long claimedX,
        long claimedY,
        UUID tokenXId,
        UUID tokenYId
) {}
