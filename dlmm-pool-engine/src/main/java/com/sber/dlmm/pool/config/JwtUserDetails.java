package com.sber.dlmm.pool.config;

import java.util.UUID;

/**
 * Lightweight view of the authenticated caller, assembled by controllers
 * from the JWT-populated Spring {@code SecurityContext} (principal = user id,
 * credentials = KYC status, first authority = role).
 *
 * <p>It exists so handler code can ask the domain-relevant questions
 * ("is this an admin?", "is KYC verified?") without re-parsing the raw
 * {@code Authentication} each time, and so the string user id is converted
 * to a {@link UUID} in one place.
 *
 * @param userId    the user's id as a string (the JWT subject / principal)
 * @param role      the caller's role with the {@code ROLE_} prefix already stripped (e.g. USER, ADMIN), or null
 * @param kycStatus the caller's KYC status claim (e.g. VERIFIED), or null
 */
public record JwtUserDetails(String userId, String role, String kycStatus) {

    /**
     * Parses {@link #userId} into a {@link UUID} for repository/service calls.
     *
     * @return the user id as a {@link UUID}
     * @throws IllegalArgumentException if {@link #userId} is not a valid UUID
     */
    public UUID userIdAsUUID() {
        return UUID.fromString(userId);
    }

    /**
     * Whether the caller has completed KYC (gates swap / liquidity actions).
     *
     * @return {@code true} if the KYC status is {@code VERIFIED}
     */
    public boolean isKycVerified() {
        return "VERIFIED".equals(kycStatus);
    }

    /**
     * Whether the caller may perform admin-only operations.
     *
     * @return {@code true} if the role is {@code ADMIN} or {@code SUPER_ADMIN}
     */
    public boolean isAdmin() {
        return "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
    }
}
