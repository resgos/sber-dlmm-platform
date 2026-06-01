package com.sber.dlmm.user.dto;

import com.sber.dlmm.common.enums.KycStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for an admin/KYC endpoint that sets a user's verification
 * state.
 *
 * @param kycStatus the new KYC status to apply to the target user; required
 *                  (must be a valid {@link KycStatus} enum value)
 */
public record UpdateKycRequest(
        @NotNull
        KycStatus kycStatus
) {
}
