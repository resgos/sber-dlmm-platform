package com.sber.dlmm.pool.controller;

import com.sber.dlmm.common.audit.UserAudit;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.pool.config.JwtUserDetails;
import com.sber.dlmm.pool.dto.ClaimRewardResponse;
import com.sber.dlmm.pool.dto.FarmRewardSummary;
import com.sber.dlmm.pool.service.LpFarmingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Sprint 17 — LP-farming rewards (reward token = Spasibo / SSPAS). Lives under
 * {@code /api/v1/pools/...} so it reuses the existing gateway pool-engine route
 * (no new route table entry) and the same JWT-populated SecurityContext.
 */
@RestController
@RequestMapping("/api/v1/pools/farming")
@Tag(name = "LP Farming", description = "LP-farming SSPAS rewards (accrual + claim)")
public class FarmingController {

    private final LpFarmingService farmingService;

    /**
     * @param farmingService service that computes accrued LP-farming rewards and settles claims
     */
    public FarmingController(LpFarmingService farmingService) {
        this.farmingService = farmingService;
    }

    /**
     * Returns the authenticated caller's farming reward summary — total
     * unclaimed SSPAS plus a per-pool breakdown. Read-only; the user is taken
     * from the JWT-populated security context so callers can only ever see
     * their own accruals.
     *
     * @return 200 with the caller's {@link FarmRewardSummary}
     * @throws com.sber.dlmm.common.exception.ForbiddenException if there is no authenticated principal
     */
    @GetMapping("/me")
    @Operation(
            summary = "Current user's farming reward summary (total unclaimed + per-pool breakdown)",
            description = "Returns the authenticated caller's accrued LP-farming SSPAS rewards: the total "
                    + "unclaimed amount plus a per-pool breakdown. Read-only; the user is resolved from the "
                    + "JWT-populated security context. Requires authentication.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Farming reward summary for the current user"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Authentication required (no authenticated principal)")
    })
    public ResponseEntity<FarmRewardSummary> mySummary() {
        JwtUserDetails user = getCurrentUser();
        return ResponseEntity.ok(farmingService.getUserSummary(user.userIdAsUUID()));
    }

    /**
     * Claims the caller's entire accrued farming balance, credits the SSPAS
     * reward token, and returns the total claimed (0 when nothing has
     * accrued). The user is resolved from the security context; the settlement
     * is performed by the service inside its own transaction.
     *
     * @return 200 with a {@link ClaimRewardResponse} carrying the total amount credited
     * @throws com.sber.dlmm.common.exception.ForbiddenException if there is no authenticated principal
     */
    @PostMapping("/claim")
    @Operation(
            summary = "Claim all accrued farming reward; credits SSPAS and returns the total",
            description = "Claims the authenticated caller's entire accrued LP-farming balance, credits the "
                    + "SSPAS reward token to their account, and returns the total amount claimed (0 when nothing "
                    + "has accrued). The user is resolved from the JWT-populated security context; the action is "
                    + "audited. Requires authentication.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reward claimed; returns the total amount credited"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Authentication required (no authenticated principal)")
    })
    @UserAudit(action = "FARMING_CLAIM", targetType = "POOL")
    public ResponseEntity<ClaimRewardResponse> claim() {
        JwtUserDetails user = getCurrentUser();
        long claimed = farmingService.claim(user.userIdAsUUID());
        return ResponseEntity.ok(new ClaimRewardResponse(claimed));
    }

    /**
     * Extracts the current caller from the JWT-populated Spring
     * {@code SecurityContext} into a {@link JwtUserDetails} (principal = user
     * id, credentials = KYC status, first authority = role with the
     * {@code ROLE_} prefix stripped).
     *
     * @return the authenticated caller's details
     * @throws ForbiddenException if there is no authenticated, non-anonymous principal
     */
    private JwtUserDetails getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ForbiddenException("Authentication required");
        }
        String userId = auth.getPrincipal().toString();
        String kycStatus = auth.getCredentials() == null ? null : auth.getCredentials().toString();
        String role = auth.getAuthorities().stream()
                .map(a -> a.getAuthority().startsWith("ROLE_") ? a.getAuthority().substring(5) : a.getAuthority())
                .findFirst()
                .orElse(null);
        return new JwtUserDetails(userId, role, kycStatus);
    }
}
