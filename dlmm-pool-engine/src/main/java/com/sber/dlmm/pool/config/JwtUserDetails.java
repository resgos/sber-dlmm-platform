package com.sber.dlmm.pool.config;

import java.util.UUID;

public record JwtUserDetails(String userId, String role, String kycStatus) {

    public UUID userIdAsUUID() {
        return UUID.fromString(userId);
    }

    public boolean isKycVerified() {
        return "VERIFIED".equals(kycStatus);
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
    }
}
