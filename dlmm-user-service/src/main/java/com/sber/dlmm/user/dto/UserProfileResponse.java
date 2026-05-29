package com.sber.dlmm.user.dto;

import com.sber.dlmm.common.enums.KycStatus;
import com.sber.dlmm.common.enums.UserRole;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String sberId,
        String email,
        String phone,
        String firstName,
        String lastName,
        KycStatus kycStatus,
        UserRole role,
        LocalDateTime createdAt,
        // Batch #6 added the column + login-flow population, but the admin
        // users-list DTO never surfaced it, so the «Последний вход» column
        // always rendered "—" even for users who just logged in (UI-test F-10).
        LocalDateTime lastLoginAt
) {
}
