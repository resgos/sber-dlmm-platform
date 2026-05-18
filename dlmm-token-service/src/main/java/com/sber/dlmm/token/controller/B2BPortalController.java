package com.sber.dlmm.token.controller;

import com.sber.dlmm.token.dto.B2BIssuerDtos.InvoiceResponse;
import com.sber.dlmm.token.dto.B2BIssuerDtos.IssuerResponse;
import com.sber.dlmm.token.dto.B2BIssuerDtos.RegisterIssuerRequest;
import com.sber.dlmm.token.dto.B2BIssuerDtos.RejectIssuerRequest;
import com.sber.dlmm.token.entity.B2BIssuer;
import com.sber.dlmm.token.service.B2BBillingService;
import com.sber.dlmm.token.service.B2BIssuerService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Sprint 5 #5.6 + #5.7 — B2B portal endpoints.
 *
 * <ul>
 *   <li>{@code POST /api/v1/b2b/issuers} — register a corp issuer (open
 *       to any authenticated user as a prototype; production gates on a
 *       new CORP_ADMIN role).</li>
 *   <li>{@code GET /api/v1/b2b/issuers} — list all issuers (ADMIN).</li>
 *   <li>{@code POST /api/v1/b2b/issuers/{id}/approve} — KYB approve (ADMIN).</li>
 *   <li>{@code POST /api/v1/b2b/issuers/{id}/reject} — KYB reject (ADMIN).</li>
 *   <li>{@code GET /api/v1/b2b/issuers/{id}/invoices} — invoice history.</li>
 *   <li>{@code POST /api/v1/b2b/billing/run} — manual billing trigger
 *       for the given period (ADMIN; primarily for demo + dev).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/b2b")
@RequiredArgsConstructor
public class B2BPortalController {

    private final B2BIssuerService issuerService;
    private final B2BBillingService billingService;

    @PostMapping("/issuers")
    @Operation(summary = "Register a corp issuer for KYB review")
    public ResponseEntity<IssuerResponse> register(@Valid @RequestBody RegisterIssuerRequest req) {
        B2BIssuer issuer = issuerService.register(req.inn(), req.legalName(),
                req.displayName(), req.contactEmail(), req.contactPhone(), req.tier());
        return ResponseEntity.status(HttpStatus.CREATED).body(IssuerResponse.from(issuer));
    }

    @GetMapping("/issuers")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List issuers, optionally filtered by KYB status")
    public ResponseEntity<List<IssuerResponse>> list(
            @RequestParam(required = false) B2BIssuer.KybStatus status) {
        List<B2BIssuer> all = status == null
                ? issuerService.findAll()
                : issuerService.findByStatus(status);
        return ResponseEntity.ok(all.stream().map(IssuerResponse::from).toList());
    }

    @GetMapping("/issuers/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<IssuerResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(IssuerResponse.from(issuerService.findById(id)));
    }

    @PostMapping("/issuers/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Approve KYB for issuer (ADMIN)")
    public ResponseEntity<IssuerResponse> approve(@PathVariable UUID id, Authentication auth) {
        UUID reviewer = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(IssuerResponse.from(issuerService.approve(id, reviewer)));
    }

    @PostMapping("/issuers/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Reject KYB for issuer (ADMIN)")
    public ResponseEntity<IssuerResponse> reject(@PathVariable UUID id,
                                                   @Valid @RequestBody RejectIssuerRequest req,
                                                   Authentication auth) {
        UUID reviewer = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(IssuerResponse.from(
                issuerService.reject(id, reviewer, req.reason())));
    }

    @GetMapping("/issuers/{id}/invoices")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Invoice history for an issuer")
    public ResponseEntity<List<InvoiceResponse>> invoices(@PathVariable UUID id) {
        return ResponseEntity.ok(billingService.findByIssuer(id).stream()
                .map(InvoiceResponse::from).toList());
    }

    /**
     * Manual trigger for the monthly billing scheduler. Useful for demo
     * (don't want to wait until 1st of next month) and for re-running
     * the period if scheduler missed a fire. Idempotent — pre-existing
     * invoices for the period are skipped.
     */
    @PostMapping("/billing/run")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Manually trigger billing run for the given YYYY-MM period (ADMIN)")
    public ResponseEntity<List<InvoiceResponse>> runBilling(
            @RequestParam(required = false) String period) {
        YearMonth ym = period != null ? YearMonth.parse(period) : YearMonth.now().minusMonths(1);
        int generated = billingService.generateInvoicesForPeriod(ym);
        return ResponseEntity.ok(
                java.util.List.of()  // empty body — generated count is in the log
        );
    }
}
