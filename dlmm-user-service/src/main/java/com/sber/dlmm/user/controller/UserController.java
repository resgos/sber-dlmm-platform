package com.sber.dlmm.user.controller;

import com.sber.dlmm.common.audit.AdminAudit;
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
import com.sber.dlmm.common.audit.AdminAuditLog;
import com.sber.dlmm.common.audit.AdminAuditService;
import com.sber.dlmm.user.entity.UserSelfRestriction;
import com.sber.dlmm.user.service.SelfRestrictionService;
import com.sber.dlmm.user.service.TwoFactorService;
import com.sber.dlmm.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final SelfRestrictionService selfRestrictionService;
    private final AdminAuditService adminAuditService;
    private final TwoFactorService twoFactorService;

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
    @AdminAudit(action = "USER_KYC_UPDATE", targetType = "USER", targetIdParam = "id")
    public ResponseEntity<UserProfileResponse> updateKycStatus(@PathVariable UUID id,
                                                                @Valid @RequestBody UpdateKycRequest request) {
        return ResponseEntity.ok(userService.updateKycStatus(id, request));
    }

    @PutMapping("/users/{id}/role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @AdminAudit(action = "USER_ROLE_UPDATE", targetType = "USER", targetIdParam = "id")
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
    @AdminAudit(action = "USER_BLOCK", targetType = "USER", targetIdParam = "id")
    public ResponseEntity<Void> blockUser(@PathVariable UUID id) {
        userService.blockUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/unblock")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @AdminAudit(action = "USER_UNBLOCK", targetType = "USER", targetIdParam = "id")
    public ResponseEntity<Void> unblockUser(@PathVariable UUID id) {
        userService.unblockUser(id);
        return ResponseEntity.noContent().build();
    }

    // ── Sprint 8 #AU-4 — admin audit log read API ──

    /**
     * Paged audit log. SUPER_ADMIN-only — same admins who can change roles
     * shouldn't be able to inspect each other's actions freely (compliance
     * isolation). Optional {@code actorUserId} / {@code targetType}+{@code targetId}
     * filters for drill-down from the admin UI.
     */
    @GetMapping("/admin/audit")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Admin audit log (Sprint 8 AU-4) — paged, optionally filtered by actor or target")
    public ResponseEntity<PageResponse<AdminAuditLog>> getAuditLog(
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        org.springframework.data.domain.Page<AdminAuditLog> result;
        if (actorUserId != null) {
            result = adminAuditService.byActor(actorUserId, page, size);
        } else if (targetType != null && targetId != null) {
            result = adminAuditService.byTarget(targetType, targetId, page, size);
        } else {
            result = adminAuditService.recent(page, size);
        }
        return ResponseEntity.ok(new PageResponse<>(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()));
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

    // ── Sprint 11 G-20 — 2FA TOTP ──────────────────────────────────────────

    /**
     * Begin 2FA enrolment. Generates a fresh secret + 10 recovery codes
     * and returns them WITHOUT persisting — the client renders the
     * QR / secret / recovery-codes UI, the user types a TOTP code from
     * their authenticator app, and the actual enable() call commits.
     *
     * <p>This separation means an abandoned setup (tab close, network
     * fail) leaves no half-state — the next /begin returns a fresh
     * secret. The user's authenticator app entry made during the
     * abandoned attempt is harmless because it was never persisted
     * server-side.
     */
    @PostMapping("/users/me/2fa/begin")
    @Operation(summary = "Begin 2FA enrolment — returns secret + recovery codes (not persisted yet)")
    public ResponseEntity<TwoFactorService.SetupChallenge> beginTwoFactor(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        String label = userService.getProfile(userId).email();
        return ResponseEntity.ok(twoFactorService.beginSetup(userId, label));
    }

    /**
     * Commit 2FA enrolment. Validates the user-typed TOTP code against
     * the secret returned by /begin, then persists the enrolment
     * (secret + bcrypt-hashed recovery codes). 400 if the code doesn't
     * validate.
     */
    @PostMapping("/users/me/2fa/enable")
    @Operation(summary = "Enable 2FA — requires valid TOTP code derived from /begin secret")
    public ResponseEntity<Void> enableTwoFactor(Authentication auth,
                                                  @Valid @RequestBody EnableTwoFactorRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        twoFactorService.enable(userId, req.secret(), req.recoveryCodes(), req.code());
        return ResponseEntity.noContent().build();
    }

    /**
     * Second-factor check during login. Accepts a 6-digit TOTP code OR
     * a recovery code (recovery codes consume on success).
     */
    @PostMapping("/users/me/2fa/verify")
    @Operation(summary = "Verify 2FA code (TOTP or recovery) during login")
    public ResponseEntity<Void> verifyTwoFactor(Authentication auth,
                                                  @Valid @RequestBody VerifyTwoFactorRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        twoFactorService.verify(userId, req.code());
        return ResponseEntity.noContent().build();
    }

    /**
     * Disable 2FA. Idempotent — returns 204 whether or not the user
     * was enrolled. Wipes the secret + recovery codes; re-enable
     * goes through /begin from scratch.
     */
    @PostMapping("/users/me/2fa/disable")
    @Operation(summary = "Disable 2FA (idempotent)")
    public ResponseEntity<Void> disableTwoFactor(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        twoFactorService.disable(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/me/2fa/status")
    @Operation(summary = "Current 2FA state: enabled + enrolment time + recovery codes remaining")
    public ResponseEntity<TwoFactorService.StatusView> twoFactorStatus(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(twoFactorService.status(userId));
    }

    public record EnableTwoFactorRequest(
            @NotBlank String secret,
            @NotNull @Size(min = 10, max = 10) List<@NotBlank String> recoveryCodes,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "TOTP code must be exactly 6 digits") String code
    ) {}

    public record VerifyTwoFactorRequest(@NotBlank String code) {}
}
