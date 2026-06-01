package com.sber.dlmm.fee.dto;

import java.util.UUID;

/**
 * Result of a {@link ClaimFeesRequest}: how much was actually credited per
 * token leg. Both amounts are {@code 0} when there was nothing unclaimed.
 *
 * <p>In the quote-only fallback (the X leg is rolled into the quote token Y),
 * {@code claimedX} is reported as {@code 0}, {@code tokenXId} as {@code null},
 * and the combined value appears under {@code claimedY} / {@code tokenYId}.
 *
 * @param positionId position the fees were claimed for
 * @param claimedX   X-token amount credited, raw ×10⁴ base units (0 if none / quote-only)
 * @param claimedY   Y-token amount credited, raw ×10⁴ base units
 * @param tokenXId   id of the X token credited; {@code null} in the quote-only fallback
 * @param tokenYId   id of the Y (quote) token credited
 */
public record ClaimFeesResponse(
        UUID positionId,
        long claimedX,
        long claimedY,
        UUID tokenXId,
        UUID tokenYId
) {}
