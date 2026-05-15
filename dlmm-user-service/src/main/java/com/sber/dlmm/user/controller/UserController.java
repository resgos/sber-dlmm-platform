package com.sber.dlmm.user.controller;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.enums.KycStatus;
import com.sber.dlmm.common.enums.UserRole;
import com.sber.dlmm.user.dto.AuthResponse;
import com.sber.dlmm.user.dto.LoginRequest;
import com.sber.dlmm.user.dto.RefreshTokenRequest;
import com.sber.dlmm.user.dto.RegisterRequest;
import com.sber.dlmm.user.dto.UpdateKycRequest;
import com.sber.dlmm.user.dto.UpdateProfileRequest;
import com.sber.dlmm.user.dto.UpdateRoleRequest;
import com.sber.dlmm.user.dto.UserProfileResponse;
import com.sber.dlmm.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/auth/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/auth/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(userService.login(request));
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(userService.refresh(request));
    }

    @GetMapping("/users/me")
    public ResponseEntity<UserProfileResponse> getMyProfile(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(userService.getProfile(userId));
    }

    @PutMapping("/users/me")
    public ResponseEntity<UserProfileResponse> updateMyProfile(Authentication authentication,
                                                                @Valid @RequestBody UpdateProfileRequest request) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(userService.updateProfile(userId, request));
    }

    @GetMapping("/users/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<UserProfileResponse> getUser(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getProfile(id));
    }

    @PutMapping("/users/{id}/kyc")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<UserProfileResponse> updateKycStatus(@PathVariable UUID id,
                                                                @Valid @RequestBody UpdateKycRequest request) {
        return ResponseEntity.ok(userService.updateKycStatus(id, request));
    }

    @PutMapping("/users/{id}/role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<UserProfileResponse> updateRole(@PathVariable UUID id,
                                                           @Valid @RequestBody UpdateRoleRequest request) {
        return ResponseEntity.ok(userService.updateRole(id, request));
    }

    @GetMapping("/users")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<PageResponse<UserProfileResponse>> searchUsers(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) KycStatus kycStatus,
            @RequestParam(required = false) UserRole role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(userService.searchUsers(query, kycStatus, role, page, size));
    }

    @PostMapping("/users/{id}/block")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> blockUser(@PathVariable UUID id) {
        userService.blockUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/unblock")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> unblockUser(@PathVariable UUID id) {
        userService.unblockUser(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Internal endpoint for service-to-service KYC checks (consumed by
     * pool-engine before swap / add-liquidity). Any authenticated principal
     * can call it — pool-engine forwards the caller's Bearer token via
     * BearerTokenForwardingFilter.
     */
    @GetMapping("/users/internal/{id}/kyc")
    public ResponseEntity<KycCheckResponse> checkKyc(@PathVariable UUID id) {
        boolean verified = userService.isKycVerified(id);
        return ResponseEntity.ok(new KycCheckResponse(verified));
    }

    public record KycCheckResponse(boolean verified) {}
}
