package com.sber.dlmm.pool.controller;

import com.sber.dlmm.common.audit.UserAudit;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.pool.config.JwtUserDetails;
import com.sber.dlmm.pool.dto.ClaimRewardResponse;
import com.sber.dlmm.pool.dto.FarmRewardSummary;
import com.sber.dlmm.pool.service.LpFarmingService;
import io.swagger.v3.oas.annotations.Operation;
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

    public FarmingController(LpFarmingService farmingService) {
        this.farmingService = farmingService;
    }

    @GetMapping("/me")
    @Operation(summary = "Current user's farming reward summary (total unclaimed + per-pool breakdown)")
    public ResponseEntity<FarmRewardSummary> mySummary() {
        JwtUserDetails user = getCurrentUser();
        return ResponseEntity.ok(farmingService.getUserSummary(user.userIdAsUUID()));
    }

    @PostMapping("/claim")
    @Operation(summary = "Claim all accrued farming reward; credits SSPAS and returns the total")
    @UserAudit(action = "FARMING_CLAIM", targetType = "POOL")
    public ResponseEntity<ClaimRewardResponse> claim() {
        JwtUserDetails user = getCurrentUser();
        long claimed = farmingService.claim(user.userIdAsUUID());
        return ResponseEntity.ok(new ClaimRewardResponse(claimed));
    }

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
