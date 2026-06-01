package com.sber.dlmm.transaction.controller;

import com.sber.dlmm.common.audit.AdminAudit;
import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.transaction.entity.OtcBlockTrade;
import com.sber.dlmm.transaction.repository.OtcBlockTradeRepository;
import com.sber.dlmm.transaction.service.OtcDeskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 9 #6.1 (M#7) — OTC desk admin workflow.
 *
 * <p>All endpoints are admin-only. State-machine driver lives in
 * {@link OtcDeskService}; this layer is thin (auth + request shape
 * → service call → response mapping).
 *
 * <p><b>Audit trail note (Sprint 10 follow-up)</b>: Sprint 8 AU-4
 * added {@code @AdminAudit} AOP to user-service but did NOT extend
 * to transaction-service (the aspect bean lives in user-service
 * only — cross-service propagation needs either (a) moving the
 * aspect + table to dlmm-common with REQUIRES_NEW + a remote
 * /admin/audit/internal endpoint to user-service, or (b) per-service
 * audit tables with cross-service replay at the dashboard layer).
 * For Sprint 9 OTC, every mutation logs via slf4j (operator-visible
 * via Loki/Promtail). Sprint 10 wires real audit log.
 */
@RestController
@RequestMapping("/api/v1/otc")
@RequiredArgsConstructor
@Tag(name = "OTC Desk", description = "Sprint 9 #6.1 — admin OTC block-trade workflow")
public class OtcDeskController {

    private final OtcDeskService otcDeskService;
    private final OtcBlockTradeRepository repository;

    /**
     * Creates a new OTC block trade in REQUESTED state. The creating admin is
     * recorded as operator. Delegates to {@link OtcDeskService#create}.
     *
     * @param auth the caller's authentication (principal = admin id)
     * @param req  validated create request (parties, token pair, amount, notes)
     * @return 201 with the created {@link OtcBlockTrade}
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
            summary = "Create new OTC block trade in REQUESTED state",
            description = "Opens a new OTC block trade in REQUESTED status from the supplied initiator, counterparty, "
                    + "token pair, and nominal amount-in. The creating admin is recorded as the operator. "
                    + "ADMIN / SUPER_ADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "OTC block trade created in REQUESTED state"),
            @ApiResponse(responseCode = "400", description = "Request body failed validation"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN / SUPER_ADMIN")
    })
    @AdminAudit(action = "OTC_CREATE", targetType = "OTC")
    public ResponseEntity<OtcBlockTrade> create(Authentication auth,
                                                 @Valid @RequestBody CreateRequest req) {
        UUID adminId = parseUserId(auth);
        OtcBlockTrade trade = otcDeskService.create(
                req.initiatorUserId(), req.counterpartyUserId(),
                req.tokenInId(), req.tokenOutId(), req.amountIn(),
                req.notes(), adminId);
        return ResponseEntity.status(HttpStatus.CREATED).body(trade);
    }

    /**
     * REQUESTED → QUOTED: attaches a quote (amount-out, price, expiry).
     *
     * @param id  id of the trade to quote
     * @param req validated quote request (amount-out, price, future expiry)
     * @return 200 with the updated {@link OtcBlockTrade}
     */
    @PostMapping("/{id}/quote")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
            summary = "REQUESTED → QUOTED: attach quote (price + expiry)",
            description = "Transitions a trade from REQUESTED to QUOTED by attaching the quoted amount-out, price, "
                    + "and an expiry instant (which must be in the future). Returns the updated trade. "
                    + "ADMIN / SUPER_ADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Quote attached; trade now QUOTED"),
            @ApiResponse(responseCode = "400", description = "Request body failed validation"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN / SUPER_ADMIN")
    })
    @AdminAudit(action = "OTC_QUOTE", targetType = "OTC", targetIdParam = "id")
    public ResponseEntity<OtcBlockTrade> quote(@Parameter(description = "OTC block trade id") @PathVariable UUID id,
                                                @Valid @RequestBody QuoteRequest req) {
        return ResponseEntity.ok(otcDeskService.quote(
                id, req.amountOut(), req.quotedPriceMicro(), req.quoteExpiresAt()));
    }

    /**
     * QUOTED → ACCEPTED: accepts the quote on the counterparty's behalf
     * (provided it has not expired).
     *
     * @param id id of the trade to accept
     * @return 200 with the updated {@link OtcBlockTrade}
     */
    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
            summary = "QUOTED → ACCEPTED: counterparty accepts the quote",
            description = "Transitions a trade from QUOTED to ACCEPTED on behalf of the counterparty, provided the "
                    + "quote has not expired. Returns the updated trade. ADMIN / SUPER_ADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Quote accepted; trade now ACCEPTED"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN / SUPER_ADMIN")
    })
    @AdminAudit(action = "OTC_ACCEPT", targetType = "OTC", targetIdParam = "id")
    public ResponseEntity<OtcBlockTrade> accept(@Parameter(description = "OTC block trade id") @PathVariable UUID id) {
        return ResponseEntity.ok(otcDeskService.accept(id));
    }

    /**
     * QUOTED → REJECTED: declines the quote on the counterparty's behalf, with
     * an optional reason. The request body is optional.
     *
     * @param id  id of the trade to reject
     * @param req optional reason wrapper, or {@code null}
     * @return 200 with the updated {@link OtcBlockTrade}
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
            summary = "QUOTED → REJECTED: counterparty declines",
            description = "Transitions a trade from QUOTED to REJECTED on behalf of the counterparty, with an "
                    + "optional free-text reason appended to the trade notes. Returns the updated trade. "
                    + "ADMIN / SUPER_ADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Quote rejected; trade now REJECTED"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN / SUPER_ADMIN")
    })
    @AdminAudit(action = "OTC_REJECT", targetType = "OTC", targetIdParam = "id")
    public ResponseEntity<OtcBlockTrade> reject(@Parameter(description = "OTC block trade id") @PathVariable UUID id,
                                                 @RequestBody(required = false) ReasonRequest req) {
        return ResponseEntity.ok(otcDeskService.reject(id, req == null ? null : req.reason()));
    }

    /**
     * ACCEPTED → SETTLED: records the settlement transaction id linking to the
     * ledger row (the transfer itself is executed out-of-band).
     *
     * @param id  id of the trade to settle
     * @param req validated settle request carrying the settlement tx id
     * @return 200 with the updated {@link OtcBlockTrade}
     */
    @PostMapping("/{id}/settle")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
            summary = "ACCEPTED → SETTLED: link the executed ledger transaction",
            description = "Transitions a trade from ACCEPTED to SETTLED and records the settlement transaction id "
                    + "that references the underlying ledger row (the actual transfer is executed out-of-band). "
                    + "Returns the updated trade. ADMIN / SUPER_ADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trade settled; now SETTLED"),
            @ApiResponse(responseCode = "400", description = "Request body failed validation"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN / SUPER_ADMIN")
    })
    @AdminAudit(action = "OTC_SETTLE", targetType = "OTC", targetIdParam = "id")
    public ResponseEntity<OtcBlockTrade> settle(@Parameter(description = "OTC block trade id") @PathVariable UUID id,
                                                 @Valid @RequestBody SettleRequest req) {
        return ResponseEntity.ok(otcDeskService.settle(id, req.settlementTxId()));
    }

    /**
     * REQUESTED|QUOTED → CANCELLED: cancels a not-yet-accepted trade, with an
     * optional reason. The request body is optional.
     *
     * @param id  id of the trade to cancel
     * @param req optional reason wrapper, or {@code null}
     * @return 200 with the updated {@link OtcBlockTrade}
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
            summary = "REQUESTED|QUOTED → CANCELLED: admin cancels",
            description = "Cancels a trade that is still REQUESTED or QUOTED (an ACCEPTED trade cannot be cancelled), "
                    + "with an optional free-text reason appended to the trade notes. Returns the updated trade. "
                    + "ADMIN / SUPER_ADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trade cancelled; now CANCELLED"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN / SUPER_ADMIN")
    })
    @AdminAudit(action = "OTC_CANCEL", targetType = "OTC", targetIdParam = "id")
    public ResponseEntity<OtcBlockTrade> cancel(@Parameter(description = "OTC block trade id") @PathVariable UUID id,
                                                 @RequestBody(required = false) ReasonRequest req) {
        return ResponseEntity.ok(otcDeskService.cancel(id, req == null ? null : req.reason()));
    }

    /**
     * Fetches one OTC block trade by id, straight from the repository.
     *
     * @param id id of the trade to fetch
     * @return 200 with the {@link OtcBlockTrade}, or 404 if none exists
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
            summary = "Get single OTC trade",
            description = "Returns one OTC block trade by id, or 404 if no trade with that id exists. "
                    + "ADMIN / SUPER_ADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OTC block trade returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN / SUPER_ADMIN"),
            @ApiResponse(responseCode = "404", description = "No OTC block trade with the given id")
    })
    public ResponseEntity<OtcBlockTrade> getOne(@Parameter(description = "OTC block trade id") @PathVariable UUID id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Lists OTC block trades, createdAt-DESC. At most one filter applies, in
     * priority order status → counterparty → initiator; with none set, all
     * trades are listed. Page index is floored at 0 and size clamped to [1, 200].
     *
     * @param status             optional status filter (highest priority)
     * @param counterpartyUserId optional counterparty filter (ignored if status set)
     * @param initiatorUserId    optional initiator filter (ignored if either above set)
     * @param page               zero-based page index (floored at 0)
     * @param size               page size (clamped to [1, 200])
     * @return 200 with a {@link PageResponse} of {@link OtcBlockTrade}
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
            summary = "List OTC trades — paged, optionally filtered by status / counterparty / initiator",
            description = "Returns a createdAt-DESC page of OTC block trades. At most one of status, counterpartyUserId, "
                    + "or initiatorUserId is applied as a filter (evaluated in that order); with none set, all trades "
                    + "are listed. Page size is capped at 200. ADMIN / SUPER_ADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of OTC block trades returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN / SUPER_ADMIN")
    })
    public ResponseEntity<PageResponse<OtcBlockTrade>> list(
            @Parameter(description = "Optional filter by trade status") @RequestParam(required = false) OtcBlockTrade.Status status,
            @Parameter(description = "Optional filter by counterparty user id (ignored if status is set)") @RequestParam(required = false) UUID counterpartyUserId,
            @Parameter(description = "Optional filter by initiator user id (ignored if status or counterparty is set)") @RequestParam(required = false) UUID initiatorUserId,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size; capped at 200") @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
        Page<OtcBlockTrade> result;
        if (status != null) {
            result = repository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else if (counterpartyUserId != null) {
            result = repository.findByCounterpartyUserIdOrderByCreatedAtDesc(counterpartyUserId, pageable);
        } else if (initiatorUserId != null) {
            result = repository.findByInitiatorUserIdOrderByCreatedAtDesc(initiatorUserId, pageable);
        } else {
            result = repository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return ResponseEntity.ok(new PageResponse<>(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()));
    }

    /**
     * Extracts the caller's user id from the authentication principal, which is
     * normally already a {@link UUID} but falls back to parsing its string form.
     *
     * @param auth the caller's authentication
     * @return the caller's user id
     */
    private static UUID parseUserId(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof UUID uuid) return uuid;
        return UUID.fromString(principal.toString());
    }

    // ── DTOs ──

    /**
     * Body for creating an OTC trade.
     *
     * @param initiatorUserId    requesting user (required)
     * @param counterpartyUserId other side (required)
     * @param tokenInId          token offered (required)
     * @param tokenOutId         token wanted (required)
     * @param amountIn           nominal amount in, raw units (must be positive)
     * @param notes              optional operator notes (≤ 1000 chars)
     */
    public record CreateRequest(
            @NotNull UUID initiatorUserId,
            @NotNull UUID counterpartyUserId,
            @NotNull UUID tokenInId,
            @NotNull UUID tokenOutId,
            @Positive long amountIn,
            @Size(max = 1000) String notes) {}

    /**
     * Body for quoting an OTC trade.
     *
     * @param amountOut        quoted amount out, raw units (must be positive)
     * @param quotedPriceMicro quoted price in micro-units (must be positive)
     * @param quoteExpiresAt   quote expiry instant (required; must be future)
     */
    public record QuoteRequest(
            @Positive long amountOut,
            @Positive long quotedPriceMicro,
            @NotNull LocalDateTime quoteExpiresAt) {}

    /**
     * Optional reason wrapper for reject/cancel.
     *
     * @param reason free-text reason (≤ 500 chars)
     */
    public record ReasonRequest(@Size(max = 500) String reason) {}

    /**
     * Body for settling an OTC trade.
     *
     * @param settlementTxId reference to the executed ledger transaction (required)
     */
    public record SettleRequest(@NotNull UUID settlementTxId) {}
}
