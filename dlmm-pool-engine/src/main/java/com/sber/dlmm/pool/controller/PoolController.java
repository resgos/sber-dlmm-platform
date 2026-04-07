package com.sber.dlmm.pool.controller;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.pool.config.JwtUserDetails;
import com.sber.dlmm.pool.dto.AddLiquidityRequest;
import com.sber.dlmm.pool.dto.AddLiquidityResponse;
import com.sber.dlmm.pool.dto.BinResponse;
import com.sber.dlmm.pool.dto.PoolDetailResponse;
import com.sber.dlmm.pool.dto.PoolResponse;
import com.sber.dlmm.pool.dto.PositionResponse;
import com.sber.dlmm.pool.dto.RemoveLiquidityRequest;
import com.sber.dlmm.pool.dto.RemoveLiquidityResponse;
import com.sber.dlmm.pool.dto.SwapQuoteRequest;
import com.sber.dlmm.pool.dto.SwapQuoteResponse;
import com.sber.dlmm.pool.dto.SwapRequest;
import com.sber.dlmm.pool.dto.SwapResponse;
import com.sber.dlmm.pool.dto.CreatePoolRequest;
import com.sber.dlmm.pool.dto.UpdateFeeParamsRequest;
import com.sber.dlmm.pool.service.LiquidityService;
import com.sber.dlmm.pool.service.PoolService;
import com.sber.dlmm.pool.service.SwapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
@RequestMapping("/api/v1/pools")
@Tag(name = "Pool Engine", description = "DLMM Liquidity Pool management, swap, and liquidity operations")
public class PoolController {

    private final PoolService poolService;
    private final LiquidityService liquidityService;
    private final SwapService swapService;

    public PoolController(PoolService poolService,
                          LiquidityService liquidityService,
                          SwapService swapService) {
        this.poolService = poolService;
        this.liquidityService = liquidityService;
        this.swapService = swapService;
    }

    @PostMapping
    @Operation(summary = "Create a new liquidity pool (ADMIN only)")
    public ResponseEntity<PoolResponse> createPool(@Valid @RequestBody CreatePoolRequest request) {
        JwtUserDetails user = getCurrentUser();
        requireAdmin(user);
        PoolResponse response = poolService.createPool(request, user.userIdAsUUID());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "Get all pools with pagination")
    public ResponseEntity<PageResponse<PoolResponse>> getAllPools(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy) {
        return ResponseEntity.ok(poolService.getAllPools(page, size, sortBy));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get pool details with bin distribution")
    public ResponseEntity<PoolDetailResponse> getPoolDetail(@PathVariable UUID id) {
        return ResponseEntity.ok(poolService.getPoolDetail(id));
    }

    @GetMapping("/{id}/bins")
    @Operation(summary = "Get pool bins in a range")
    public ResponseEntity<List<BinResponse>> getPoolBins(
            @PathVariable UUID id,
            @RequestParam int from,
            @RequestParam int to) {
        return ResponseEntity.ok(poolService.getPoolBins(id, from, to));
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause a pool (ADMIN only)")
    public ResponseEntity<PoolResponse> pausePool(@PathVariable UUID id) {
        requireAdmin(getCurrentUser());
        return ResponseEntity.ok(poolService.pausePool(id));
    }

    @PostMapping("/{id}/emergency-shutdown")
    @Operation(summary = "Emergency shutdown a pool (ADMIN only)")
    public ResponseEntity<PoolResponse> emergencyShutdown(@PathVariable UUID id) {
        requireAdmin(getCurrentUser());
        return ResponseEntity.ok(poolService.emergencyShutdown(id));
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume a pool (ADMIN only)")
    public ResponseEntity<PoolResponse> resumePool(@PathVariable UUID id) {
        requireAdmin(getCurrentUser());
        return ResponseEntity.ok(poolService.resumePool(id));
    }

    @PutMapping("/{id}/fee-params")
    @Operation(summary = "Update pool fee parameters (ADMIN only)")
    public ResponseEntity<PoolResponse> updateFeeParams(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateFeeParamsRequest request) {
        requireAdmin(getCurrentUser());
        return ResponseEntity.ok(poolService.updateFeeParams(id,
                request.baseFeeBps(), request.maxVariableFeeBps(), request.decayPeriodSeconds()));
    }

    @PostMapping("/add-liquidity")
    @Operation(summary = "Add liquidity to a pool (KYC verified users)")
    public ResponseEntity<AddLiquidityResponse> addLiquidity(
            @Valid @RequestBody AddLiquidityRequest request) {
        JwtUserDetails user = getCurrentUser();
        AddLiquidityResponse response = liquidityService.addLiquidity(request, user.userIdAsUUID());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/remove-liquidity")
    @Operation(summary = "Remove liquidity from a position (KYC verified users)")
    public ResponseEntity<RemoveLiquidityResponse> removeLiquidity(
            @Valid @RequestBody RemoveLiquidityRequest request) {
        JwtUserDetails user = getCurrentUser();
        return ResponseEntity.ok(liquidityService.removeLiquidity(request, user.userIdAsUUID()));
    }

    @GetMapping("/positions/me")
    @Operation(summary = "Get current user's active positions")
    public ResponseEntity<List<PositionResponse>> getUserPositions() {
        JwtUserDetails user = getCurrentUser();
        return ResponseEntity.ok(liquidityService.getUserPositions(user.userIdAsUUID()));
    }

    @PostMapping("/swap")
    @Operation(summary = "Execute a token swap (KYC verified users)")
    public ResponseEntity<SwapResponse> swap(@Valid @RequestBody SwapRequest request) {
        JwtUserDetails user = getCurrentUser();
        return ResponseEntity.ok(swapService.swap(request, user.userIdAsUUID()));
    }

    @PostMapping("/swap/quote")
    @Operation(summary = "Get a swap quote without executing")
    public ResponseEntity<SwapQuoteResponse> swapQuote(@Valid @RequestBody SwapQuoteRequest request) {
        return ResponseEntity.ok(swapService.quote(request));
    }

    private JwtUserDetails getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getDetails() == null || !(auth.getDetails() instanceof JwtUserDetails)) {
            throw new ForbiddenException("Authentication required");
        }
        return (JwtUserDetails) auth.getDetails();
    }

    private void requireAdmin(JwtUserDetails user) {
        if (!user.isAdmin()) {
            throw new ForbiddenException("Admin access required");
        }
    }
}
