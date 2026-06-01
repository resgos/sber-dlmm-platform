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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/**
 * REST surface for the identity domain — the user-service's primary HTTP
 * controller. It is a thin adapter: it resolves the caller from the JWT
 * principal, delegates to {@link UserService} / {@link SelfRestrictionService}
 * / {@link TwoFactorService} / {@code AdminAuditService}, and shapes the
 * response. All business rules and invariants live in those services.
 *
 * <p>Endpoint groups (mounted under {@code /api/v1}):
 * <ul>
 *   <li><b>Auth</b> — register / login / refresh / logout. These are the
 *       token-issuing endpoints and are public (no access token required);
 *       the gateway skips auth for them.</li>
 *   <li><b>Self profile</b> — {@code /users/me} read + update, resolved from
 *       the authenticated principal.</li>
 *   <li><b>Admin user management</b> — get-by-id, KYC update, role update,
 *       search, block / unblock. Gated by {@code @PreAuthorize} on
 *       ADMIN / SUPER_ADMIN and audited via {@code @AdminAudit}.</li>
 *   <li><b>Internal</b> — KYC and self-restriction checks consumed
 *       service-to-service by pool-engine (Bearer token forwarded).</li>
 *   <li><b>115-FZ self-restriction</b> and <b>2FA TOTP</b> — caller-scoped
 *       enrolment / verification flows.</li>
 * </ul>
 *
 * <p>The convention throughout: {@code (UUID) authentication.getPrincipal()}
 * is the authenticated user's id, populated by the shared JWT filter.
 * Security-sensitive method behaviour (audit, role gating, idempotency) is
 * documented per method; the Swagger {@code @Operation}/{@code @ApiResponses}
 * annotations carry the wire-level contract.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Users & Auth", description = "Authentication, user profiles, KYC, admin user management, self-restriction and 2FA")
public class UserController {

    private final UserService userService;
    private final SelfRestrictionService selfRestrictionService;
    private final AdminAuditService adminAuditService;
    private final TwoFactorService twoFactorService;

    /**
     * Registers a new account and returns an authenticated token pair so the
     * client is logged in immediately. Public endpoint. Delegates uniqueness
     * checks and hashing to {@link UserService#register}; responds 201 on
     * success.
     *
     * @param request the validated registration payload
     * @return 201 with the issued tokens and the new user's profile
     */
    @PostMapping("/auth/register")
    @Operation(summary = "Register a new user",
            description = "Creates a new USER account (KYC starts PENDING) and returns access + refresh tokens "
                    + "plus the new profile. Public endpoint — no authentication required. The sberId and "
                    + "email must be unique.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User created; tokens and profile returned"),
            @ApiResponse(responseCode = "400", description = "Validation error in the request body"),
            @ApiResponse(responseCode = "409", description = "A user with this sberId or email already exists")
    })
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Authenticates by email + password and returns a fresh token pair. Public
     * endpoint. Delegates to {@link UserService#login}, which masks
     * unknown-email vs. wrong-password as one 401 to avoid user enumeration.
     *
     * @param request the validated login payload (email + password)
     * @return 200 with the issued tokens and the user's profile
     */
    @PostMapping("/auth/login")
    @Operation(summary = "Log in with email and password",
            description = "Authenticates by email + password and returns access + refresh tokens plus the "
                    + "user profile. Public endpoint — no authentication required. Updates last_login_at on success.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated; tokens and profile returned"),
            @ApiResponse(responseCode = "400", description = "Validation error in the request body"),
            @ApiResponse(responseCode = "401", description = "Invalid email or password")
    })
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(userService.login(request));
    }

    /**
     * Rotates a valid refresh token into a new access+refresh pair carrying the
     * user's current claims. Public endpoint (no access token needed). Delegates
     * to {@link UserService#refresh}, which rejects a non-refresh token.
     *
     * @param request the validated request carrying the refresh token
     * @return 200 with the new token pair and the user's profile
     */
    @PostMapping("/auth/refresh")
    @Operation(summary = "Exchange a refresh token for a new token pair",
            description = "Validates the supplied refresh token and issues a fresh access + refresh token pair "
                    + "with the user's current claims. Public endpoint — no access token required. The token must "
                    + "be a valid, non-expired refresh token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New token pair and profile returned"),
            @ApiResponse(responseCode = "400", description = "Validation error in the request body"),
            @ApiResponse(responseCode = "401", description = "Token is invalid, expired, or not a refresh token"),
            @ApiResponse(responseCode = "404", description = "User referenced by the token no longer exists")
    })
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
     *
     * @param request the servlet request, read for the {@code Authorization: Bearer} header
     * @param body    optional body carrying a refresh token to revoke as well (may be null)
     * @return 204 No Content, unconditionally
     */
    @PostMapping("/auth/logout")
    @Operation(summary = "Log out — revoke the presented access (and optionally refresh) token",
            description = "Reads the Bearer access token from the Authorization header and adds its jti to the "
                    + "Redis denylist for the token's remaining lifetime; if a refresh token is supplied in the body "
                    + "it is revoked too. Idempotent and always returns 204, even on missing or malformed tokens, so "
                    + "it does not leak token validity. Public endpoint.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Logout processed (always returned, even if no valid token was present)")
    })
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

    /**
     * Optional logout body letting the client surrender its refresh token too.
     *
     * @param refreshToken the refresh token to also revoke, or null to revoke only the access token
     */
    public record LogoutRequest(String refreshToken) {}

    /**
     * Returns the authenticated caller's own profile, resolved from the JWT
     * principal. Requires a valid access token.
     *
     * @param authentication the Spring authentication whose principal is the caller's user id
     * @return 200 with the caller's profile
     */
    @GetMapping("/users/me")
    @Operation(summary = "Get the authenticated user's own profile",
            description = "Returns the profile of the currently authenticated caller, resolved from the JWT principal. "
                    + "Requires a valid access token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Caller's profile"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "404", description = "Authenticated user no longer exists")
    })
    public ResponseEntity<UserProfileResponse> getMyProfile(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(userService.getProfile(userId));
    }

    /**
     * Applies a partial update to the caller's own profile (null fields left
     * unchanged). Requires a valid access token.
     *
     * @param authentication the authentication whose principal is the caller's user id
     * @param request        the validated partial-profile update
     * @return 200 with the updated profile
     */
    @PutMapping("/users/me")
    @Operation(summary = "Update the authenticated user's own profile",
            description = "Updates the caller's own email, phone, first name and/or last name; null fields are left "
                    + "unchanged. Requires a valid access token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated profile"),
            @ApiResponse(responseCode = "400", description = "Validation error in the request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "404", description = "Authenticated user no longer exists")
    })
    public ResponseEntity<UserProfileResponse> updateMyProfile(Authentication authentication,
                                                                @Valid @RequestBody UpdateProfileRequest request) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(userService.updateProfile(userId, request));
    }

    /**
     * Admin lookup of an arbitrary user's profile by id. Restricted to
     * ADMIN / SUPER_ADMIN via {@code @PreAuthorize}.
     *
     * @param id the id of the user to fetch
     * @return 200 with that user's profile
     */
    @GetMapping("/users/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get any user's profile by id (ADMIN/SUPER_ADMIN only)",
            description = "Returns the profile of the user with the given id. Restricted to ADMIN and SUPER_ADMIN roles.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User profile"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN/SUPER_ADMIN role"),
            @ApiResponse(responseCode = "404", description = "No user with this id")
    })
    public ResponseEntity<UserProfileResponse> getUser(@Parameter(description = "User id")
                                                       @PathVariable UUID id) {
        return ResponseEntity.ok(userService.getProfile(id));
    }

    /**
     * Admin sets a user's KYC status; a transition to VERIFIED emits a Kafka
     * KYC-verified event downstream. Restricted to ADMIN / SUPER_ADMIN and
     * recorded by {@code @AdminAudit} (action {@code USER_KYC_UPDATE}, target
     * the path {@code id}).
     *
     * @param id      the id of the user whose KYC status to set
     * @param request the validated request carrying the target KYC status
     * @return 200 with the updated profile
     */
    @PutMapping("/users/{id}/kyc")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @AdminAudit(action = "USER_KYC_UPDATE", targetType = "USER", targetIdParam = "id")
    @Operation(summary = "Update a user's KYC status (ADMIN/SUPER_ADMIN only)",
            description = "Sets the target user's KYC status. When set to VERIFIED, emits a Kafka KYC-verified event. "
                    + "Restricted to ADMIN and SUPER_ADMIN roles; the action is audit-logged.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated profile with the new KYC status"),
            @ApiResponse(responseCode = "400", description = "Validation error in the request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN/SUPER_ADMIN role"),
            @ApiResponse(responseCode = "404", description = "No user with this id")
    })
    public ResponseEntity<UserProfileResponse> updateKycStatus(@Parameter(description = "User id")
                                                                @PathVariable UUID id,
                                                                @Valid @RequestBody UpdateKycRequest request) {
        return ResponseEntity.ok(userService.updateKycStatus(id, request));
    }

    /**
     * Admin changes a user's platform role. Restricted to SUPER_ADMIN only
     * (stricter than the KYC/block endpoints) and recorded by
     * {@code @AdminAudit} (action {@code USER_ROLE_UPDATE}). The new role takes
     * effect on the target's next token issuance.
     *
     * @param id      the id of the user whose role to change
     * @param request the validated request carrying the target role
     * @return 200 with the updated profile
     */
    @PutMapping("/users/{id}/role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @AdminAudit(action = "USER_ROLE_UPDATE", targetType = "USER", targetIdParam = "id")
    @Operation(summary = "Update a user's platform role (SUPER_ADMIN only)",
            description = "Changes the target user's platform role. Restricted to SUPER_ADMIN; the action is audit-logged.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated profile with the new role"),
            @ApiResponse(responseCode = "400", description = "Validation error in the request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN"),
            @ApiResponse(responseCode = "404", description = "No user with this id")
    })
    public ResponseEntity<UserProfileResponse> updateRole(@Parameter(description = "User id")
                                                           @PathVariable UUID id,
                                                           @Valid @RequestBody UpdateRoleRequest request) {
        return ResponseEntity.ok(userService.updateRole(id, request));
    }

    /**
     * Admin paged user search/list. Filters apply in priority order — query
     * &gt; kycStatus &gt; role &gt; all (see {@link UserService#searchUsers}).
     * Restricted to ADMIN / SUPER_ADMIN.
     *
     * @param query     free-text name/email search; highest-priority filter
     * @param kycStatus KYC filter, used when {@code query} is blank
     * @param role      role filter, used when {@code query} and {@code kycStatus} are absent
     * @param page      zero-based page index (default 0)
     * @param size      page size (default 20)
     * @return 200 with a page of user profiles
     */
    @GetMapping("/users")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Search/list users with pagination (ADMIN/SUPER_ADMIN only)",
            description = "Returns a paged list of users. Filters are applied in priority order: free-text query "
                    + "(matches name/email), else KYC status, else role, else all users. Restricted to ADMIN and "
                    + "SUPER_ADMIN roles.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged user list"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN/SUPER_ADMIN role")
    })
    public ResponseEntity<PageResponse<UserProfileResponse>> searchUsers(
            @Parameter(description = "Free-text search over name/email; takes priority over the other filters")
            @RequestParam(required = false) String query,
            @Parameter(description = "Filter by KYC status (used when query is blank)")
            @RequestParam(required = false) KycStatus kycStatus,
            @Parameter(description = "Filter by platform role (used when query and kycStatus are blank)")
            @RequestParam(required = false) UserRole role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(userService.searchUsers(query, kycStatus, role, page, size));
    }

    /**
     * Admin blocks a user (demote to USER + KYC REJECTED, emit user-blocked
     * event). Restricted to ADMIN / SUPER_ADMIN; a SUPER_ADMIN target is
     * protected. Recorded by {@code @AdminAudit} (action {@code USER_BLOCK}).
     *
     * @param id the id of the user to block
     * @return 204 No Content on success
     */
    @PostMapping("/users/{id}/block")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @AdminAudit(action = "USER_BLOCK", targetType = "USER", targetIdParam = "id")
    @Operation(summary = "Block a user (ADMIN/SUPER_ADMIN only)",
            description = "Blocks the target user by demoting them to USER and setting KYC status to REJECTED, then "
                    + "emits a Kafka user-blocked event. Restricted to ADMIN/SUPER_ADMIN; a SUPER_ADMIN target cannot "
                    + "be blocked. The action is audit-logged.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "User blocked"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the role, or the target is a SUPER_ADMIN"),
            @ApiResponse(responseCode = "404", description = "No user with this id")
    })
    public ResponseEntity<Void> blockUser(@Parameter(description = "User id to block")
                                          @PathVariable UUID id) {
        userService.blockUser(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Admin unblocks a user (resets KYC to PENDING; does not restore any
     * stripped role). Restricted to ADMIN / SUPER_ADMIN. Recorded by
     * {@code @AdminAudit} (action {@code USER_UNBLOCK}).
     *
     * @param id the id of the user to unblock
     * @return 204 No Content on success
     */
    @PostMapping("/users/{id}/unblock")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @AdminAudit(action = "USER_UNBLOCK", targetType = "USER", targetIdParam = "id")
    @Operation(summary = "Unblock a user (ADMIN/SUPER_ADMIN only)",
            description = "Reverses a block by resetting the target user's KYC status to PENDING. Restricted to "
                    + "ADMIN/SUPER_ADMIN; the action is audit-logged.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "User unblocked"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN/SUPER_ADMIN role"),
            @ApiResponse(responseCode = "404", description = "No user with this id")
    })
    public ResponseEntity<Void> unblockUser(@Parameter(description = "User id to unblock")
                                            @PathVariable UUID id) {
        userService.unblockUser(id);
        return ResponseEntity.noContent().build();
    }

    // ── Sprint 8 #AU-4 — admin audit log read API ──

    /**
     * Paged audit log. SUPER_ADMIN-only — same admins who can change roles
     * shouldn't be able to inspect each other's actions freely (compliance
     * isolation). Optional {@code actorUserId} / {@code targetType}+{@code targetId}
     * filters for drill-down from the admin UI.
     *
     * @param actorUserId filter by the acting admin; highest-priority filter when present
     * @param targetType  target entity type, used together with {@code targetId}
     * @param targetId    target entity id, used together with {@code targetType}
     * @param page        zero-based page index (default 0)
     * @param size        page size (default 50)
     * @return 200 with a page of audit-log entries
     */
    @GetMapping("/admin/audit")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Admin audit log (Sprint 8 AU-4) — paged, optionally filtered by actor or target",
            description = "Returns a paged admin audit log. Filtered by actorUserId if present, else by "
                    + "targetType+targetId if both present, otherwise the most recent entries. SUPER_ADMIN-only "
                    + "for compliance isolation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged audit log entries"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller is not a SUPER_ADMIN")
    })
    public ResponseEntity<PageResponse<AdminAuditLog>> getAuditLog(
            @Parameter(description = "Filter by the actor (admin) user id; takes priority over target filters")
            @RequestParam(required = false) UUID actorUserId,
            @Parameter(description = "Target entity type (e.g. USER, POOL); used with targetId")
            @RequestParam(required = false) String targetType,
            @Parameter(description = "Target entity id; used together with targetType")
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
     *
     * @param id the id of the user whose KYC to check
     * @return 200 with {@code {verified:true}} only when KYC is VERIFIED; {@code false} for an unknown user
     */
    @GetMapping("/users/internal/{id}/kyc")
    @Operation(summary = "Internal: check whether a user's KYC is verified",
            description = "Service-to-service endpoint consumed by pool-engine before swap / add-liquidity. Returns "
                    + "{verified:true} only when the user's KYC status is VERIFIED; an unknown user yields "
                    + "{verified:false}. Any authenticated principal may call (pool-engine forwards the caller's Bearer token).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "KYC verification flag for the user"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<KycCheckResponse> checkKyc(@Parameter(description = "User id to check")
                                                     @PathVariable UUID id) {
        boolean verified = userService.isKycVerified(id);
        return ResponseEntity.ok(new KycCheckResponse(verified));
    }

    /**
     * Response body of the internal KYC check.
     *
     * @param verified true if the queried user's KYC status is VERIFIED
     */
    public record KycCheckResponse(boolean verified) {}

    // ── Sprint 6 #6.7 — самозапрет (115-ФЗ amendment 2024) ──

    /**
     * Set self-restriction on the calling user. Idempotent — re-call
     * while already restricted returns the existing SET row.
     *
     * <p>The reason defaults to a Russian "set by user" string when no body is
     * supplied. Responds 201 with the SET row.
     *
     * @param auth the authentication whose principal is the caller's user id
     * @param req  optional body carrying a free-text reason (may be null)
     * @return 201 with the SET self-restriction row
     */
    @PostMapping("/users/me/self-restriction/set")
    @Operation(summary = "Установить самозапрет на новые позиции (115-ФЗ)",
            description = "Sets a self-restriction (115-FZ) on the authenticated caller, blocking new positions. "
                    + "Idempotent — re-calling while already restricted returns the existing SET row. An optional "
                    + "reason may be supplied in the body. Requires a valid access token.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Self-restriction is set (existing row if already restricted)"),
            @ApiResponse(responseCode = "400", description = "Validation error in the request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
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
     *
     * <p>Does not lift the restriction; it stays active until the cooling
     * period elapses and {@code lift-finalise} is called. The caller must
     * currently be restricted.
     *
     * @param auth the authentication whose principal is the caller's user id
     * @param req  optional body carrying a free-text reason (may be null)
     * @return 200 with the LIFT_REQUESTED row, carrying its effective-at date
     */
    @PostMapping("/users/me/self-restriction/lift-request")
    @Operation(summary = "Запросить снятие самозапрета (7-дневный период охлаждения)",
            description = "Requests lifting of the caller's self-restriction, starting the cooling period "
                    + "(default 7 days). The restriction stays active until the cooling period elapses and the lift "
                    + "is finalised. The caller must currently have an active restriction. Requires a valid access token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lift requested; cooling period started"),
            @ApiResponse(responseCode = "400", description = "Validation error in the request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "500", description = "Caller has no active restriction to lift")
    })
    public ResponseEntity<UserSelfRestriction> requestLiftSelfRestriction(
            Authentication auth,
            @Valid @RequestBody(required = false) SetSelfRestrictionRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        String reason = req != null ? req.reason() : "Запрос пользователя";
        return ResponseEntity.ok(selfRestrictionService.requestLift(userId, reason));
    }

    /**
     * Finalise lift after cooling period elapsed. Throws 400 if too early.
     *
     * <p>Requires a prior {@code lift-request} whose cooling period has passed;
     * on success the restriction becomes inactive.
     *
     * @param auth the authentication whose principal is the caller's user id
     * @return 200 with the LIFTED row
     */
    @PostMapping("/users/me/self-restriction/lift-finalise")
    @Operation(summary = "Финализировать снятие самозапрета (после периода охлаждения)",
            description = "Finalises lifting of the caller's self-restriction once the cooling period from a prior "
                    + "lift-request has elapsed. Fails if there is no pending lift-request or the cooling period has not "
                    + "yet passed. Requires a valid access token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Restriction lifted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "500", description = "No pending lift-request, or cooling period has not elapsed")
    })
    public ResponseEntity<UserSelfRestriction> finaliseLiftSelfRestriction(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(selfRestrictionService.finaliseLift(userId));
    }

    /**
     * Returns the caller's current self-restriction state (active flag) plus
     * the full append-only event history, for display in the account UI.
     *
     * @param auth the authentication whose principal is the caller's user id
     * @return 200 with the active flag and the ordered history
     */
    @GetMapping("/users/me/self-restriction")
    @Operation(summary = "Текущий статус самозапрета + полная история",
            description = "Returns the caller's current self-restriction state (active flag) together with the full "
                    + "append-only history of SET / LIFT_REQUESTED / LIFTED events. Requires a valid access token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active flag and full history"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
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
     *
     * @param id the id of the user whose restriction state to check
     * @return 200 with {@code {active:true/false}} for the user
     */
    @GetMapping("/users/internal/{id}/self-restriction-active")
    @Operation(summary = "Internal: check whether a user's self-restriction is active",
            description = "Service-to-service endpoint used by pool-engine to gate swap / add-liquidity / hedge on the "
                    + "user's self-restriction state. Returns {active:true/false}. Any authenticated principal may call "
                    + "(Bearer token forwarded via the common filter).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Self-restriction active flag for the user"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<SelfRestrictionActiveResponse> checkSelfRestrictionActive(
            @Parameter(description = "User id to check")
            @PathVariable UUID id) {
        return ResponseEntity.ok(new SelfRestrictionActiveResponse(selfRestrictionService.isActive(id)));
    }

    /**
     * Optional request body for set / lift-request, carrying a free-text reason.
     *
     * @param reason caller-supplied reason (max 500 chars), or null to use a default
     */
    public record SetSelfRestrictionRequest(@Size(max = 500) String reason) {}

    /**
     * Status response combining the active flag with the full event history.
     *
     * @param active  whether the caller currently has an active self-restriction
     * @param history the ordered (oldest-first) restriction event history
     */
    public record SelfRestrictionStatus(boolean active, java.util.List<UserSelfRestriction> history) {}

    /**
     * Minimal response for the internal self-restriction-active check.
     *
     * @param active true if the queried user's self-restriction is active
     */
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
     *
     * @param auth the authentication whose principal is the caller's user id
     * @return 200 with the setup challenge (secret, otpauth URI, recovery codes)
     */
    @PostMapping("/users/me/2fa/begin")
    @Operation(summary = "Begin 2FA enrolment — returns secret + recovery codes (not persisted yet)",
            description = "Generates a fresh TOTP secret, otpauth URI and 10 recovery codes for the authenticated "
                    + "caller and returns them WITHOUT persisting; the client must echo them back to /enable. Fails if "
                    + "2FA is already enabled (disable first). Requires a valid access token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Setup challenge (secret, otpauth URI, recovery codes)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "409", description = "2FA is already enabled for this user")
    })
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
     *
     * @param auth the authentication whose principal is the caller's user id
     * @param req  the validated payload echoing back the secret, recovery codes, and a TOTP code
     * @return 204 No Content once 2FA is enabled
     */
    @PostMapping("/users/me/2fa/enable")
    @Operation(summary = "Enable 2FA — requires valid TOTP code derived from /begin secret",
            description = "Commits 2FA enrolment: validates the user-typed TOTP code against the /begin secret, then "
                    + "persists the secret and bcrypt-hashed recovery codes. The body must carry the secret, exactly 10 "
                    + "recovery codes and a 6-digit code. Requires a valid access token.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "2FA enabled"),
            @ApiResponse(responseCode = "400", description = "Invalid setup payload or TOTP code did not validate"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "409", description = "2FA is already enabled for this user")
    })
    public ResponseEntity<Void> enableTwoFactor(Authentication auth,
                                                  @Valid @RequestBody EnableTwoFactorRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        twoFactorService.enable(userId, req.secret(), req.recoveryCodes(), req.code());
        return ResponseEntity.noContent().build();
    }

    /**
     * Second-factor check during login. Accepts a 6-digit TOTP code OR
     * a recovery code (recovery codes consume on success).
     *
     * @param auth the authentication whose principal is the caller's user id
     * @param req  the validated payload carrying the TOTP or recovery code
     * @return 204 No Content when the code is accepted
     */
    @PostMapping("/users/me/2fa/verify")
    @Operation(summary = "Verify 2FA code (TOTP or recovery) during login",
            description = "Second-factor check for the authenticated caller. A 6-digit input is treated as a TOTP code; "
                    + "anything else is treated as a recovery code (consumed on success). Returns 204 on success. "
                    + "Requires a valid access token and an existing 2FA enrolment.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Code accepted"),
            @ApiResponse(responseCode = "400", description = "Validation error in the request body"),
            @ApiResponse(responseCode = "401", description = "Missing/invalid authentication, or the 2FA code did not match"),
            @ApiResponse(responseCode = "409", description = "2FA is not enabled for this user")
    })
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
     *
     * @param auth the authentication whose principal is the caller's user id
     * @return 204 No Content (whether or not the user was enrolled)
     */
    @PostMapping("/users/me/2fa/disable")
    @Operation(summary = "Disable 2FA (idempotent)",
            description = "Disables 2FA for the authenticated caller, wiping the secret and recovery codes. Idempotent — "
                    + "returns 204 whether or not the user was enrolled. Requires a valid access token.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "2FA disabled (or was already disabled)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<Void> disableTwoFactor(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        twoFactorService.disable(userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reports the caller's 2FA state — enabled flag, enrolment time, and number
     * of recovery codes remaining — so the client can drive the 2FA settings UI.
     *
     * @param auth the authentication whose principal is the caller's user id
     * @return 200 with the current {@link TwoFactorService.StatusView}
     */
    @GetMapping("/users/me/2fa/status")
    @Operation(summary = "Current 2FA state: enabled + enrolment time + recovery codes remaining",
            description = "Returns the caller's 2FA status: whether enabled, the enrolment timestamp, and how many "
                    + "recovery codes remain. Requires a valid access token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current 2FA status view"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<TwoFactorService.StatusView> twoFactorStatus(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(twoFactorService.status(userId));
    }

    /**
     * Request body for {@code /2fa/enable}: the values from {@code /begin}
     * echoed back, plus a TOTP code proving the authenticator app is loaded.
     * Bean-validation enforces the shape (non-blank secret, exactly 10
     * recovery codes, a 6-digit code) before the service runs.
     *
     * @param secret        the base32 secret returned by {@code /begin}
     * @param recoveryCodes the exactly-10 recovery codes returned by {@code /begin}
     * @param code          a 6-digit TOTP code from the user's authenticator app
     */
    public record EnableTwoFactorRequest(
            @NotBlank String secret,
            @NotNull @Size(min = 10, max = 10) List<@NotBlank String> recoveryCodes,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "TOTP code must be exactly 6 digits") String code
    ) {}

    /**
     * Request body for {@code /2fa/verify}: a single credential, either a
     * 6-digit TOTP code or a recovery code.
     *
     * @param code the TOTP or recovery code to verify (must be non-blank)
     */
    public record VerifyTwoFactorRequest(@NotBlank String code) {}
}
