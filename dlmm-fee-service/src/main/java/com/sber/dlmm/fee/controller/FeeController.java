package com.sber.dlmm.fee.controller;

import com.sber.dlmm.common.audit.UserAudit;
import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.fee.dto.ClaimFeesRequest;
import com.sber.dlmm.fee.dto.ClaimFeesResponse;
import com.sber.dlmm.fee.dto.FeeAccrualDto;
import com.sber.dlmm.fee.dto.FeesSummaryResponse;
import com.sber.dlmm.fee.service.FeeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST entry point for LP trading fees under {@code /api/v1/fees}. Thin layer over
 * {@link FeeService}: it resolves the caller's user id from the JWT-backed
 * {@link Authentication} and delegates; no business logic lives here.
 *
 * <p>Authorization model: the {@code /me/**} and {@code /claim} endpoints always act on
 * the authenticated principal's own fees (the user id comes from the token, never from
 * the request), so a caller can only ever see or claim their own money. The
 * {@code /user/{userId}/summary} endpoint is the one cross-user read and is gated to
 * ADMIN/SUPER_ADMIN via {@code @PreAuthorize}.
 *
 * <p>The claim endpoint is money-moving; its idempotency and quote-only consolidation
 * semantics are documented on {@link FeeService#claimFees(ClaimFeesRequest, UUID, boolean)}.
 */
@RestController
@RequestMapping("/api/v1/fees")
@RequiredArgsConstructor
@Tag(name = "Fees", description = "LP fee accruals: per-user summary, claim, and history")
public class FeeController {

    private final FeeService feeService;

    /**
     * GET {@code /me/summary} — returns the authenticated user's aggregated fee summary.
     * The user id is read from the JWT principal, so callers only ever see their own fees.
     *
     * @param authentication the JWT-backed security context; its name is the caller's user id
     * @return 200 with the {@link FeesSummaryResponse} for the calling user
     */
    @GetMapping("/me/summary")
    @Operation(summary = "Get the authenticated user's fee summary",
            description = "Returns the calling user's aggregated fee accruals grouped by pool "
                    + "(per-token earned and unclaimed totals, plus platform-wide claimed/unclaimed sums). "
                    + "The user id is taken from the JWT — callers only ever see their own fees.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Fee summary returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<FeesSummaryResponse> getUserFeesSummary(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        FeesSummaryResponse response = feeService.getUserFeesSummary(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * POST {@code /claim} — claims the caller's unclaimed fees on a position and credits
     * their balances. Delegates to {@link FeeService#claimFees(ClaimFeesRequest, UUID, boolean)};
     * see that method for the money-safety contract (in-transaction credit, idempotency,
     * and quote-only consolidation/fallback).
     *
     * <p>The request is bean-validated ({@code @Valid}) and the action is audited
     * ({@code @UserAudit}). The user id comes from the JWT, never the body.
     *
     * @param request        the position to claim plus an optional idempotency key (validated)
     * @param quoteOnly      when {@code true}, consolidate the payout into the quote token (Y)
     * @param authentication the JWT-backed security context supplying the caller's user id
     * @return 200 with the {@link ClaimFeesResponse} (all-zero amounts when nothing was unclaimed)
     */
    @PostMapping("/claim")
    @Operation(summary = "Claim accrued fees for a position",
            description = "Marks the calling user's unclaimed fees on the given position as claimed and credits "
                    + "the amounts to their token balances. By default both pool tokens (X and Y) are credited "
                    + "separately. When quoteOnly=true the X-side fee is converted at the pool's current price and "
                    + "the whole claim is credited as a single quote token (Y) — claimedX is then reported as 0 and "
                    + "the combined value appears in claimedY; if the pool cannot be read it transparently falls back "
                    + "to the standard split credit so fees are never lost. Supplying idempotencyKey makes retries "
                    + "safe — a key that was already processed is rejected with 409. The user id is taken from the JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Fees claimed (claimedX/claimedY both 0 when nothing was unclaimed)"),
            @ApiResponse(responseCode = "400", description = "Validation failed (e.g. missing positionId)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "409", description = "Idempotency key has already been processed")
    })
    @UserAudit(action = "CLAIM_FEES", targetType = "POSITION")
    public ResponseEntity<ClaimFeesResponse> claimFees(@Valid @RequestBody ClaimFeesRequest request,
                                                       @Parameter(description = "Consolidate the claim into the pool's quote token (Y): "
                                                               + "the X-fee is converted at the current price and credited as a single token")
                                                       @RequestParam(required = false, defaultValue = "false") boolean quoteOnly,
                                                       Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        ClaimFeesResponse response = feeService.claimFees(request, userId, quoteOnly);
        return ResponseEntity.ok(response);
    }

    /**
     * GET {@code /me/history} — returns a page of the caller's fee accrual records
     * (claimed and unclaimed), newest first, optionally filtered to one pool. The user id
     * is taken from the JWT.
     *
     * @param poolId         optional pool filter; {@code null} returns all of the caller's pools
     * @param page           zero-based page index (defaults to 0)
     * @param size           page size (defaults to 20)
     * @param authentication the JWT-backed security context supplying the caller's user id
     * @return 200 with a {@link PageResponse} of {@link FeeAccrualDto}
     */
    @GetMapping("/me/history")
    @Operation(summary = "Get the authenticated user's fee accrual history",
            description = "Returns a paginated list of the calling user's fee accrual records (claimed and unclaimed), "
                    + "newest first. Optionally filtered to a single pool. The user id is taken from the JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of fee accruals returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<PageResponse<FeeAccrualDto>> getFeeHistory(
            @Parameter(description = "Optional pool id to filter the history to a single pool")
            @RequestParam(required = false) UUID poolId,
            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size")
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        PageResponse<FeeAccrualDto> response = feeService.getFeeHistory(userId, poolId, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * GET {@code /user/{userId}/summary} — admin-only fee summary for an arbitrary user.
     * The one endpoint here that reads another user's fees, so it is restricted to
     * ADMIN/SUPER_ADMIN via {@code @PreAuthorize}; the target user is the path variable
     * rather than the JWT principal.
     *
     * @param userId the user whose fee summary to fetch
     * @return 200 with the target user's {@link FeesSummaryResponse}
     */
    @GetMapping("/user/{userId}/summary")
    @Operation(summary = "Get any user's fee summary (admin only)",
            description = "Same aggregated fee summary as /me/summary but for an arbitrary user id. "
                    + "Restricted to ADMIN and SUPER_ADMIN roles.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Fee summary returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<FeesSummaryResponse> getUserFeesSummaryByAdmin(
            @Parameter(description = "Id of the user whose fee summary to fetch")
            @PathVariable UUID userId) {
        FeesSummaryResponse response = feeService.getUserFeesSummary(userId);
        return ResponseEntity.ok(response);
    }
}
