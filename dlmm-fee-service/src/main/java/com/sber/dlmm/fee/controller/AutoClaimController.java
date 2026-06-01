package com.sber.dlmm.fee.controller;

import com.sber.dlmm.fee.dto.AutoClaimLogDto;
import com.sber.dlmm.fee.dto.AutoClaimPolicyDto;
import com.sber.dlmm.fee.repository.AutoClaimLogRepository;
import com.sber.dlmm.fee.service.AutoClaimPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Sprint 12 G-16 — exposes the auto-claim policy + history to the
 * authenticated user. Mounted under {@code /api/v1/fees/} so the
 * existing gateway route ({@code Path=/api/v1/fees/**}) proxies us
 * without any new route rule. The task spec calls these
 * {@code /api/v1/users/me/auto-claim-policy} in user-prose; the
 * actual gateway-compatible path is {@code /api/v1/fees/me/auto-claim-policy}.
 */
@RestController
@RequestMapping("/api/v1/fees/me/auto-claim-policy")
@RequiredArgsConstructor
@Tag(name = "Auto-Claim Policy", description = "The authenticated user's automatic fee-claim policy and its firing history")
public class AutoClaimController {

    private final AutoClaimPolicyService policyService;
    private final AutoClaimLogRepository logRepository;

    /**
     * GET {@code /api/v1/fees/me/auto-claim-policy} — returns the caller's auto-claim
     * policy, or the platform default if they have never saved one. Read-only; the user
     * id comes from the JWT.
     *
     * @param authentication the JWT-backed security context supplying the caller's user id
     * @return 200 with the stored {@link AutoClaimPolicyDto} or the default
     */
    @GetMapping
    @Operation(summary = "Get the authenticated user's auto-claim policy",
            description = "Returns the calling user's auto-claim policy (enabled flag, threshold amount, daily cap, "
                    + "and skipped pool ids). If the user has never configured one, a default policy is returned. "
                    + "The user id is taken from the JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Policy (or the default) returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<AutoClaimPolicyDto> getPolicy(Authentication authentication) {
        UUID userId = currentUser(authentication);
        return ResponseEntity.ok(policyService.getOrDefault(userId));
    }

    /**
     * PUT {@code /api/v1/fees/me/auto-claim-policy} — upserts the caller's auto-claim
     * policy from the request body and echoes the stored (normalised) result so the
     * client needs no follow-up GET. Body is bean-validated ({@code @Valid}); the user id
     * comes from the JWT.
     *
     * @param dto            the desired policy (enabled, threshold, daily cap, skip-pool ids)
     * @param authentication the JWT-backed security context supplying the caller's user id
     * @return 200 with the persisted {@link AutoClaimPolicyDto}
     */
    @PutMapping
    @Operation(summary = "Create or update the authenticated user's auto-claim policy",
            description = "Upserts the calling user's auto-claim policy from the supplied body (enabled flag, "
                    + "threshold amount, daily cap where 0 means unlimited, and the list of pool ids to skip) and "
                    + "returns the stored policy. The user id is taken from the JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Policy created or updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed (e.g. negative threshold or daily cap)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<AutoClaimPolicyDto> upsertPolicy(@Valid @RequestBody AutoClaimPolicyDto dto,
                                                            Authentication authentication) {
        UUID userId = currentUser(authentication);
        return ResponseEntity.ok(policyService.upsert(userId, dto));
    }

    /**
     * DELETE {@code /api/v1/fees/me/auto-claim-policy} — resets the caller's policy to the
     * platform default (deletes their row) and returns that default. Idempotent. The user
     * id comes from the JWT.
     *
     * @param authentication the JWT-backed security context supplying the caller's user id
     * @return 200 with the default {@link AutoClaimPolicyDto}
     */
    @DeleteMapping
    @Operation(summary = "Reset the authenticated user's auto-claim policy to defaults",
            description = "Resets the calling user's auto-claim policy to the platform default and returns the "
                    + "resulting default policy. The user id is taken from the JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Policy reset; the default policy is returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<AutoClaimPolicyDto> resetPolicy(Authentication authentication) {
        UUID userId = currentUser(authentication);
        return ResponseEntity.ok(policyService.reset(userId));
    }

    /**
     * GET {@code /api/v1/fees/me/auto-claim-policy/history} — returns the caller's most
     * recent auto-claim log entries (SUCCESS and FAILURE), newest first. The requested
     * {@code limit} is clamped to 1..100 to bound the query. The user id comes from the JWT.
     *
     * @param limit          requested number of entries; clamped to the range 1..100
     * @param authentication the JWT-backed security context supplying the caller's user id
     * @return 200 with the list of {@link AutoClaimLogDto} entries
     */
    @GetMapping("/history")
    @Operation(summary = "Get the authenticated user's auto-claim firing history",
            description = "Returns the most recent auto-claim log entries for the calling user, newest first. "
                    + "The requested limit is clamped to the range 1..100. The user id is taken from the JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of auto-claim log entries returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<List<AutoClaimLogDto>> getHistory(
            @Parameter(description = "Maximum number of entries to return; clamped to 1..100")
            @RequestParam(defaultValue = "20") int limit,
            Authentication authentication) {
        UUID userId = currentUser(authentication);
        int safeLimit = Math.min(Math.max(1, limit), 100);
        List<AutoClaimLogDto> entries = logRepository
                .findByUserIdOrderByFiredAtDesc(userId, PageRequest.of(0, safeLimit))
                .getContent()
                .stream()
                .map(AutoClaimLogDto::from)
                .toList();
        return ResponseEntity.ok(entries);
    }

    /**
     * Matches the JwtAuthenticationFilter contract — principal is the
     * UUID object since Sprint 9; {@code .getName()} stringifies it
     * back. Same pattern used by {@link FeeController}.
     */
    private static UUID currentUser(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID uuid) return uuid;
        return UUID.fromString(authentication.getName());
    }
}
