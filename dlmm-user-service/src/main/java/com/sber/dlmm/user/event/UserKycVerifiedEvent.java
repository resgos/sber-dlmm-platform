package com.sber.dlmm.user.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserKycVerifiedEvent(
        UUID userId,
        LocalDateTime verifiedAt
) {
}
