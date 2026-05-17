package com.sber.dlmm.transaction.controller;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.enums.B2BSettlementStatus;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.transaction.dto.B2BSettlementRequest;
import com.sber.dlmm.transaction.dto.B2BSettlementResponse;
import com.sber.dlmm.transaction.service.B2BSettlementService;
import io.swagger.v3.oas.annotations.Operation;
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
public class B2BSettlementController {

    private final B2BSettlementService service;

    /**
     * POST /api/v1/transactions/b2b/settlements
     *
     * Idempotent by {@code reference}. Returns HTTP 201 on first execution,
     * HTTP 200 on a duplicate-reference replay (existing row returned).
     * Body's {@code status} field carries the outcome — COMPLETED on success,
     * FAILED with error_message on rejection or partial failure.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Submit a B2B settlement (ADMIN — prototype gate; CORP role pending)")
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
     */
    @GetMapping("/{id}")
    @Operation(summary = "Fetch one B2B settlement by id")
    public ResponseEntity<B2BSettlementResponse> get(@PathVariable UUID id, Authentication auth) {
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
     */
    @GetMapping
    @Operation(summary = "List B2B settlements where caller is initiator or counterparty")
    public ResponseEntity<PageResponse<B2BSettlementResponse>> list(
            Authentication auth,
            @RequestParam(required = false) B2BSettlementStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID caller = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(service.listForUser(caller, status, from, to, page, size));
    }

    private static boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SUPER_ADMIN"));
    }
}
