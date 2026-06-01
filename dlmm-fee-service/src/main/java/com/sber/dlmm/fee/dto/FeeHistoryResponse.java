package com.sber.dlmm.fee.dto;

import java.util.List;

/**
 * A page/listing of fee accruals plus their cross-token totals, for the
 * fee-history endpoint.
 *
 * @param accruals the accrual rows in this response
 * @param totalX   sum of the X-token leg over {@code accruals}, raw ×10⁴ base units
 * @param totalY   sum of the Y-token leg over {@code accruals}, raw ×10⁴ base units
 */
public record FeeHistoryResponse(
        List<FeeAccrualDto> accruals,
        long totalX,
        long totalY
) {}
