package com.sber.dlmm.pool.controller;

import com.sber.dlmm.common.audit.UserAudit;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.pool.config.JwtUserDetails;
import com.sber.dlmm.pool.dto.CreateLimitOrderRequest;
import com.sber.dlmm.pool.dto.LimitOrderResponse;
import com.sber.dlmm.pool.entity.LimitOrderStatus;
import com.sber.dlmm.pool.service.LimitOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Sprint 16 (Meteora parity) — limit orders on DLMM pools. Lives under
 * {@code /api/v1/pools/...} so it reuses the existing gateway pool-engine route
 * (no new route table entry) and the same JWT-populated SecurityContext.
 */
@RestController
@RequestMapping("/api/v1/pools/limit-orders")
@Tag(name = "Limit Orders", description = "DLMM limit orders (escrow-settled at a target price)")
public class LimitOrderController {

    private final LimitOrderService limitOrderService;

    public LimitOrderController(LimitOrderService limitOrderService) {
        this.limitOrderService = limitOrderService;
    }

    @PostMapping
    @Operation(summary = "Place a limit order (KYC verified users)")
    @UserAudit(action = "LIMIT_ORDER_CREATE", targetType = "POOL")
    public ResponseEntity<LimitOrderResponse> create(@Valid @RequestBody CreateLimitOrderRequest request) {
        JwtUserDetails user = getCurrentUser();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(limitOrderService.createLimitOrder(request, user.userIdAsUUID()));
    }

    @GetMapping("/me")
    @Operation(summary = "Current user's limit orders (optionally filter by pool or status)")
    public ResponseEntity<List<LimitOrderResponse>> myOrders(
            @RequestParam(required = false) UUID poolId,
            @RequestParam(required = false) LimitOrderStatus status) {
        JwtUserDetails user = getCurrentUser();
        UUID userId = user.userIdAsUUID();
        List<LimitOrderResponse> orders = (poolId != null)
                ? limitOrderService.getUserPoolOrders(userId, poolId)
                : limitOrderService.getUserOrders(userId, status);
        return ResponseEntity.ok(orders);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel an open limit order and refund the escrow")
    @UserAudit(action = "LIMIT_ORDER_CANCEL", targetType = "POOL")
    public ResponseEntity<LimitOrderResponse> cancel(@PathVariable UUID id) {
        JwtUserDetails user = getCurrentUser();
        return ResponseEntity.ok(limitOrderService.cancelLimitOrder(id, user.userIdAsUUID()));
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
