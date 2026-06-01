package com.sber.dlmm.user.dto;

import com.sber.dlmm.common.enums.KycStatus;
import com.sber.dlmm.common.enums.UserRole;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Public projection of a {@link com.sber.dlmm.user.entity.User} returned to
 * clients (profile endpoints, the admin users-list, and nested inside
 * {@link AuthResponse}).
 *
 * <p>This is the safe view of a user: it deliberately omits the
 * {@code passwordHash} and any other secret — only non-sensitive identity,
 * status and timestamp fields are exposed.
 *
 * @param id          the user's surrogate id (UUID)
 * @param sberId      Sber ecosystem identifier
 * @param email       contact email
 * @param phone       contact phone ({@code +7XXXXXXXXXX})
 * @param firstName   given name
 * @param lastName    family name
 * @param kycStatus   current KYC verification state
 * @param role        authorization role
 * @param createdAt   when the account was created
 * @param lastLoginAt timestamp of the user's most recent successful login, or
 *                    {@code null} if they have never logged in
 */
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
