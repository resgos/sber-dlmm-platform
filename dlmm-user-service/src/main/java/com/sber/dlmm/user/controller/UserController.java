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
import com.sber.dlmm.user.entity.UserSelfRestriction;
import com.sber.dlmm.user.service.SelfRestrictionService;
import com.sber.dlmm.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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
    private final SelfRestrictionService selfRestrictionService;

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

    /**
     * Sprint 8 AU-3 — JWT revocation (audit C-5).
     *
     * <p>Extracts the Bearer access token from the Authorization header and
     * adds its jti to the Redis denylist for the remaining lifetime. If the
     * client also supplies a refresh token in the body, that's revoked too —
     * otherwise the user could refresh their way back in.
     *
     * <p>Returns 204 always (idempotent) — even on malformed input we don't
     * leak whether the token was valid. Audit-relevant outcomes are logged.
     */
    @PostMapping("/auth/logout")
    @Operation(summary = "Log out — revoke the presented access (and optionally refresh) token")
    public ResponseEntity<Void> logout(HttpServletRequest request,
                                        @RequestBody(required = false) LogoutRequest body) {
        String header = request.getHeader("Authorization");
        String accessToken = (header != null && header.startsWith("Bearer "))
                ? header.substring("Bearer ".length())
                : null;
        String refreshToken = body != null ? body.refreshToken() : null;
        if (accessToken != null) {
            userService.logout(accessToken, refreshToken);
        }
        return ResponseEntity.noContent().build();
    }

    public record LogoutRequest(String refreshToken) {}

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

    // ── Sprint 6 #6.7 — самозапрет (115-ФЗ amendment 2024) ──

    /**
     * Set self-restriction on the calling user. Idempotent — re-call
     * while already restricted returns the existing SET row.
     */
    @PostMapping("/users/me/self-restriction/set")
    @Operation(summary = "Установить самозапрет на новые позиции (115-ФЗ)")
    public ResponseEntity<UserSelfRestriction> setSelfRestriction(
            Authentication auth,
            @Valid @RequestBody(required = false) SetSelfRestrictionRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        String reason = req != null ? req.reason() : "Установлен пользователем";
        UserSelfRestriction row = selfRestrictionService.set(userId, reason);
        return ResponseEntity.status(HttpStatus.CREATED).body(row);
    }

    /**
     * Request to lift self-restriction — starts the cooling period
     * (default 7 days per ЦБ РФ guidance).
     */
    @PostMapping("/users/me/self-restriction/lift-request")
    @Operation(summary = "Запросить снятие самозапрета (7-дневный период охлаждения)")
    public ResponseEntity<UserSelfRestriction> requestLiftSelfRestriction(
            Authentication auth,
            @Valid @RequestBody(required = false) SetSelfRestrictionRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        String reason = req != null ? req.reason() : "Запрос пользователя";
        return ResponseEntity.ok(selfRestrictionService.requestLift(userId, reason));
    }

    /** Finalise lift after cooling period elapsed. Throws 400 if too early. */
    @PostMapping("/users/me/self-restriction/lift-finalise")
    @Operation(summary = "Финализировать снятие самозапрета (после периода охлаждения)")
    public ResponseEntity<UserSelfRestriction> finaliseLiftSelfRestriction(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(selfRestrictionService.finaliseLift(userId));
    }

    @GetMapping("/users/me/self-restriction")
    @Operation(summary = "Текущий статус самозапрета + полная история")
    public ResponseEntity<SelfRestrictionStatus> getMySelfRestriction(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(new SelfRestrictionStatus(
                selfRestrictionService.isActive(userId),
                selfRestrictionService.history(userId)));
    }

    /**
     * Internal endpoint for pool-engine to gate swap / add-liquidity /
     * hedge on the user's self-restriction state. Same auth pattern as
     * the KYC check above — Bearer-forwarded via dlmm-common filter.
     */
    @GetMapping("/users/internal/{id}/self-restriction-active")
    public ResponseEntity<SelfRestrictionActiveResponse> checkSelfRestrictionActive(@PathVariable UUID id) {
        return ResponseEntity.ok(new SelfRestrictionActiveResponse(selfRestrictionService.isActive(id)));
    }

    public record SetSelfRestrictionRequest(@Size(max = 500) String reason) {}
    public record SelfRestrictionStatus(boolean active, java.util.List<UserSelfRestriction> history) {}
    public record SelfRestrictionActiveResponse(boolean active) {}
}
