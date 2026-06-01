package com.sber.dlmm.transaction.controller;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.enums.B2BSettlementStatus;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.transaction.dto.B2BSettlementRequest;
import com.sber.dlmm.transaction.dto.B2BSettlementResponse;
import com.sber.dlmm.transaction.service.B2BSettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 4 #4.6 — B2B settlement API for corp clients (Sber Treasury,
 * MOEX clearing, future pilot accounts).
 *
 * <p>Currently ADMIN-gated as a prototype safeguard — production should
 * introduce a CORP_OPERATOR role with KYB verification and per-org spending
 * caps. Tracked in the Sprint 5+ backlog.
 *
 * <p>Initiator (debit side) is always the JWT subject — operators cannot
 * debit other corps' accounts via this endpoint. Counterparty (credit
 * side) comes from the body.
 */
@RestController
@RequestMapping("/api/v1/transactions/b2b/settlements")
@RequiredArgsConstructor
@Tag(name = "B2B Settlements", description = "Corp-to-corp same-token settlements over DLMM token rails (idempotent by reference)")
public class B2BSettlementController {

    private final B2BSettlementService service;

    /**
     * POST /api/v1/transactions/b2b/settlements
     *
     * Idempotent by {@code reference}. Returns HTTP 201 on first execution,
     * HTTP 200 on a duplicate-reference replay (existing row returned).
     * Body's {@code status} field carries the outcome — COMPLETED on success,
     * FAILED with error_message on rejection or partial failure.
     *
     * <p>Note: the current implementation always returns 201 (the caller
     * distinguishes new vs existing via the returned id). The debit side is
     * always the JWT subject.
     *
     * @param auth the caller's authentication (principal = initiator id)
     * @param req  validated settlement request (counterparty, token, amount,
     *             reference, notes)
     * @return 201 with the resulting {@link B2BSettlementResponse} (status
     *         field reflects COMPLETED / FAILED)
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
            summary = "Submit a B2B settlement (ADMIN — prototype gate; CORP role pending)",
            description = "Executes a same-token corp-to-corp transfer. The debit side (initiator) is always the JWT "
                    + "subject; the credit side (counterparty) comes from the body. Idempotent by reference — a "
                    + "duplicate reference returns the existing row. Always responds 201; the outcome (COMPLETED, or "
                    + "FAILED with an error message) is carried in the response body's status field. ADMIN / "
                    + "SUPER_ADMIN only as a prototype safeguard.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Settlement accepted; body carries the resulting status (COMPLETED / FAILED)"),
            @ApiResponse(responseCode = "400", description = "Request body failed validation, or counterparty equals initiator"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN / SUPER_ADMIN")
    })
    public ResponseEntity<B2BSettlementResponse> submit(
            Authentication auth,
            @Valid @RequestBody B2BSettlementRequest req) {
        UUID initiator = (UUID) auth.getPrincipal();
        B2BSettlementResponse resp = service.submit(req, initiator);
        // Always 201 for now — the caller distinguishes new-vs-existing via
        // the returned id (they generated the reference, they know).
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * GET /api/v1/transactions/b2b/settlements/{id}
     *
     * Settlement detail. Caller must be either side of the transfer
     * (initiator OR counterparty) — admins can read any.
     *
     * @param id   settlement id to fetch
     * @param auth the caller's authentication (principal = caller id)
     * @return 200 with the {@link B2BSettlementResponse}
     * @throws ForbiddenException if the caller is neither a party to the
     *         settlement nor an admin
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "Fetch one B2B settlement by id",
            description = "Returns one B2B settlement. The caller must be either side of the transfer (initiator or "
                    + "counterparty); ADMIN / SUPER_ADMIN may read any settlement.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settlement returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is neither a party to the settlement nor an admin")
    })
    public ResponseEntity<B2BSettlementResponse> get(
            @Parameter(description = "B2B settlement id") @PathVariable UUID id, Authentication auth) {
        B2BSettlementResponse resp = service.get(id);
        UUID caller = (UUID) auth.getPrincipal();
        boolean isAdmin = isAdmin(auth);
        if (!isAdmin && !resp.fromUserId().equals(caller) && !resp.toUserId().equals(caller)) {
            throw new ForbiddenException("Access denied to B2B settlement " + id);
        }
        return ResponseEntity.ok(resp);
    }

    /**
     * GET /api/v1/transactions/b2b/settlements?status=...&from=...&to=...
     *
     * Lists settlements where the JWT subject is either side. Optional
     * status + date filters. Paginated, default 20/page sorted by
     * createdAt DESC. Admins still get only their own through this endpoint
     * — admin-wide listing (any user) is a Sprint 5+ admin-ui need.
     *
     * @param auth   the caller's authentication (principal = caller id)
     * @param status optional status filter
     * @param from   optional inclusive lower bound on createdAt
     * @param to     optional inclusive upper bound on createdAt
     * @param page   zero-based page index (default 0)
     * @param size   page size (default 20)
     * @return 200 with a {@link PageResponse} of the caller's settlements
     */
    @GetMapping
    @Operation(
            summary = "List B2B settlements where caller is initiator or counterparty",
            description = "Returns a paginated, createdAt-DESC page of settlements where the JWT subject is either "
                    + "side of the transfer, optionally filtered by status and a created-at date window. Even admins "
                    + "see only their own settlements through this endpoint.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of the caller's settlements returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
    })
    public ResponseEntity<PageResponse<B2BSettlementResponse>> list(
            Authentication auth,
            @Parameter(description = "Optional filter by settlement status") @RequestParam(required = false) B2BSettlementStatus status,
            @Parameter(description = "Optional inclusive lower bound on createdAt (ISO-8601 local date-time)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Optional inclusive upper bound on createdAt (ISO-8601 local date-time)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        UUID caller = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(service.listForUser(caller, status, from, to, page, size));
    }

    /**
     * True if the caller holds the ADMIN or SUPER_ADMIN role, used to widen
     * read access beyond the parties of a settlement.
     *
     * @param auth the caller's authentication
     * @return {@code true} if the caller has {@code ROLE_ADMIN} or
     *         {@code ROLE_SUPER_ADMIN}
     */
    private static boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SUPER_ADMIN"));
    }
}
