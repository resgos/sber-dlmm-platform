package com.sber.dlmm.user.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UserProfileResponse user
) {
}
