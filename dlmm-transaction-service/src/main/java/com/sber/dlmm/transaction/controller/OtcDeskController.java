package com.sber.dlmm.transaction.controller;

import com.sber.dlmm.common.audit.AdminAudit;
import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.transaction.entity.OtcBlockTrade;
import com.sber.dlmm.transaction.repository.OtcBlockTradeRepository;
import com.sber.dlmm.transaction.service.OtcDeskService;
import io.swagger.v3.oas.annotations.Operation;
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

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Create new OTC block trade in REQUESTED state")
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

    @PostMapping("/{id}/quote")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "REQUESTED → QUOTED: attach quote (price + expiry)")
    @AdminAudit(action = "OTC_QUOTE", targetType = "OTC", targetIdParam = "id")
    public ResponseEntity<OtcBlockTrade> quote(@PathVariable UUID id,
                                                @Valid @RequestBody QuoteRequest req) {
        return ResponseEntity.ok(otcDeskService.quote(
                id, req.amountOut(), req.quotedPriceMicro(), req.quoteExpiresAt()));
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "QUOTED → ACCEPTED: counterparty accepts the quote")
    @AdminAudit(action = "OTC_ACCEPT", targetType = "OTC", targetIdParam = "id")
    public ResponseEntity<OtcBlockTrade> accept(@PathVariable UUID id) {
        return ResponseEntity.ok(otcDeskService.accept(id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "QUOTED → REJECTED: counterparty declines")
    @AdminAudit(action = "OTC_REJECT", targetType = "OTC", targetIdParam = "id")
    public ResponseEntity<OtcBlockTrade> reject(@PathVariable UUID id,
                                                 @RequestBody(required = false) ReasonRequest req) {
        return ResponseEntity.ok(otcDeskService.reject(id, req == null ? null : req.reason()));
    }

    @PostMapping("/{id}/settle")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "ACCEPTED → SETTLED: link the executed ledger transaction")
    @AdminAudit(action = "OTC_SETTLE", targetType = "OTC", targetIdParam = "id")
    public ResponseEntity<OtcBlockTrade> settle(@PathVariable UUID id,
                                                 @Valid @RequestBody SettleRequest req) {
        return ResponseEntity.ok(otcDeskService.settle(id, req.settlementTxId()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "REQUESTED|QUOTED → CANCELLED: admin cancels")
    @AdminAudit(action = "OTC_CANCEL", targetType = "OTC", targetIdParam = "id")
    public ResponseEntity<OtcBlockTrade> cancel(@PathVariable UUID id,
                                                 @RequestBody(required = false) ReasonRequest req) {
        return ResponseEntity.ok(otcDeskService.cancel(id, req == null ? null : req.reason()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get single OTC trade")
    public ResponseEntity<OtcBlockTrade> getOne(@PathVariable UUID id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List OTC trades — paged, optionally filtered by status / counterparty / initiator")
    public ResponseEntity<PageResponse<OtcBlockTrade>> list(
            @RequestParam(required = false) OtcBlockTrade.Status status,
            @RequestParam(required = false) UUID counterpartyUserId,
            @RequestParam(required = false) UUID initiatorUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
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

    private static UUID parseUserId(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof UUID uuid) return uuid;
        return UUID.fromString(principal.toString());
    }

    // ── DTOs ──

    public record CreateRequest(
            @NotNull UUID initiatorUserId,
            @NotNull UUID counterpartyUserId,
            @NotNull UUID tokenInId,
            @NotNull UUID tokenOutId,
            @Positive long amountIn,
            @Size(max = 1000) String notes) {}

    public record QuoteRequest(
            @Positive long amountOut,
            @Positive long quotedPriceMicro,
            @NotNull LocalDateTime quoteExpiresAt) {}

    public record ReasonRequest(@Size(max = 500) String reason) {}

    public record SettleRequest(@NotNull UUID settlementTxId) {}
}
