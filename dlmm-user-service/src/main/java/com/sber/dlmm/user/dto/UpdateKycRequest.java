package com.sber.dlmm.user.dto;

import com.sber.dlmm.common.enums.KycStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateKycRequest(
        @NotNull
        KycStatus kycStatus
) {
}
