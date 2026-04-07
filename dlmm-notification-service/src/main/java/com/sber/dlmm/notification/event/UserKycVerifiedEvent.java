package com.sber.dlmm.notification.event;

import java.util.UUID;

public record UserKycVerifiedEvent(
        UUID userId,
        String email,
        String fullName
) {}
