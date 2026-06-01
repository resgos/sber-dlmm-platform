package com.sber.dlmm.fee.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wire format for the /auto-claim-policy endpoints. Mirrors the
 * frontend {@code AutoClaimPolicy} interface (enabled / threshold /
 * dailyCap / skipPoolIds) so swap-in is a single fetch() replacement
 * on the client. {@code skipPoolIds} round-trips as a JSON array;
 * the entity stores it as CSV (transparent to the API).
 *
 * @param enabled         master on/off switch for the user's auto-claim (required)
 * @param thresholdAmount minimum unclaimed total (X+Y) before the scheduler
 *                        claims, in raw ×10⁴ base units; must be ≥ 0 (required)
 * @param dailyCap        max auto-claims per rolling 24h window; {@code 0} = unlimited.
 *                        A plain count, not scaled
 * @param skipPoolIds     pool ids (as strings) to exclude from auto-claim;
 *                        {@code null}/empty means no exceptions
 */
public record AutoClaimPolicyDto(
        @NotNull
        Boolean enabled,
        @NotNull
        @DecimalMin(value = "0", inclusive = true, message = "thresholdAmount must be ≥ 0")
        BigDecimal thresholdAmount,
        @Min(value = 0, message = "dailyCap must be ≥ 0 (0 = unlimited)")
        int dailyCap,
        List<String> skipPoolIds
) {
}
