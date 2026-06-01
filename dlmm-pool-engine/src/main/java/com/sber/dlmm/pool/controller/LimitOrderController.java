package com.sber.dlmm.pool.controller;

import com.sber.dlmm.common.audit.UserAudit;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.pool.config.JwtUserDetails;
import com.sber.dlmm.pool.dto.CreateLimitOrderRequest;
import com.sber.dlmm.pool.dto.LimitOrderResponse;
import com.sber.dlmm.pool.entity.LimitOrderStatus;
import com.sber.dlmm.pool.service.LimitOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

    /**
     * @param limitOrderService service that places, lists, and cancels limit orders and manages their escrow
     */
    public LimitOrderController(LimitOrderService limitOrderService) {
        this.limitOrderService = limitOrderService;
    }

    /**
     * Places an escrow-settled limit order on a DLMM pool that fills
     * automatically when the market reaches the target price (SELL escrows X,
     * BUY escrows Y). The caller is resolved from the security context and must
     * be KYC-verified and not self-restricted (115-ФЗ); the input is moved into
     * escrow on placement. Idempotent on the request's idempotency key — a
     * repeat returns the previously created order.
     *
     * @param request the validated order details (pool, side, amount, limit price, idempotency key)
     * @return 201 with the created (or previously created) {@link LimitOrderResponse}
     */
    @PostMapping
    @Operation(
            summary = "Place a limit order (KYC verified users)",
            description = "Creates an escrow-settled limit order on a DLMM pool that fills automatically when the "
                    + "market reaches the target price. SELL escrows token X (pays Y); BUY escrows token Y (pays X). "
                    + "The input amount is deducted from the caller's balance into escrow on placement. The caller is "
                    + "resolved from the JWT-populated security context and must be KYC-verified and not self-restricted "
                    + "(115-ФЗ); the target pool must exist and be ACTIVE. Supports an idempotency key — a repeat with "
                    + "the same key returns the previously created order. Returns the created order.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Limit order placed (or existing order returned for a repeated idempotency key)"),
            @ApiResponse(responseCode = "400", description = "Validation failed, invalid limit price / amount, or pool is not active"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Not authenticated, KYC not verified, self-restricted (115-ФЗ), or idempotency key belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Target pool not found")
    })
    @UserAudit(action = "LIMIT_ORDER_CREATE", targetType = "POOL")
    public ResponseEntity<LimitOrderResponse> create(@Valid @RequestBody CreateLimitOrderRequest request) {
        JwtUserDetails user = getCurrentUser();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(limitOrderService.createLimitOrder(request, user.userIdAsUUID()));
    }

    /**
     * Lists the authenticated caller's limit orders, newest first, optionally
     * filtered by pool or by status. If {@code poolId} is supplied it takes
     * precedence and the status filter is ignored. The user is resolved from
     * the security context, so only their own orders are returned.
     *
     * @param poolId optional pool filter; when present, returns only this pool's orders and the status filter is ignored
     * @param status optional status filter (e.g. OPEN, FILLED, CANCELLED); applied only when {@code poolId} is absent
     * @return 200 with the matching list of {@link LimitOrderResponse}
     * @throws com.sber.dlmm.common.exception.ForbiddenException if there is no authenticated principal
     */
    @GetMapping("/me")
    @Operation(
            summary = "Current user's limit orders (optionally filter by pool or status)",
            description = "Returns the authenticated caller's limit orders, newest first. Optionally filter by "
                    + "pool (poolId) or by order status. If poolId is supplied it takes precedence and the status "
                    + "filter is ignored. The user is resolved from the JWT-populated security context. Requires "
                    + "authentication.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of the current user's limit orders"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Authentication required (no authenticated principal)")
    })
    public ResponseEntity<List<LimitOrderResponse>> myOrders(
            @Parameter(description = "Optional pool filter; when present, returns only this pool's orders and ignores the status filter")
            @RequestParam(required = false) UUID poolId,
            @Parameter(description = "Optional status filter (e.g. OPEN, FILLED, CANCELLED); applied only when poolId is absent")
            @RequestParam(required = false) LimitOrderStatus status) {
        JwtUserDetails user = getCurrentUser();
        UUID userId = user.userIdAsUUID();
        List<LimitOrderResponse> orders = (poolId != null)
                ? limitOrderService.getUserPoolOrders(userId, poolId)
                : limitOrderService.getUserOrders(userId, status);
        return ResponseEntity.ok(orders);
    }

    /**
     * Cancels an OPEN limit order owned by the caller and atomically refunds
     * the escrowed input. Only the owner may cancel, and only while the order
     * is still OPEN; if a background watcher fills it first, the cancel loses
     * the optimistic-lock race and is rejected (surfaced as 400).
     *
     * @param id id of the limit order to cancel; must be OPEN and owned by the caller
     * @return 200 with the cancelled {@link LimitOrderResponse}
     * @throws com.sber.dlmm.common.exception.ForbiddenException if there is no authenticated principal
     */
    @DeleteMapping("/{id}")
    @Operation(
            summary = "Cancel an open limit order and refund the escrow",
            description = "Cancels an OPEN limit order owned by the authenticated caller and atomically refunds the "
                    + "escrowed input back to their balance. Only the order's owner may cancel it, and only while it is "
                    + "still OPEN. If a background watcher fills the order first, the cancel loses the optimistic-lock "
                    + "race and is rejected (the order is no longer open) — surfaced as a 400. Returns the cancelled order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order cancelled and escrow refunded"),
            @ApiResponse(responseCode = "400", description = "Order is not OPEN, or it just filled (lost the optimistic-lock race)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication token"),
            @ApiResponse(responseCode = "403", description = "Not authenticated, or the order belongs to another user"),
            @ApiResponse(responseCode = "404", description = "Limit order not found")
    })
    @UserAudit(action = "LIMIT_ORDER_CANCEL", targetType = "POOL")
    public ResponseEntity<LimitOrderResponse> cancel(
            @Parameter(description = "ID of the limit order to cancel; must be OPEN and owned by the caller")
            @PathVariable UUID id) {
        JwtUserDetails user = getCurrentUser();
        return ResponseEntity.ok(limitOrderService.cancelLimitOrder(id, user.userIdAsUUID()));
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
