package com.sber.dlmm.fee.dto;

import java.util.List;

public record FeeHistoryResponse(
        List<FeeAccrualDto> accruals,
        long totalX,
        long totalY
) {}
