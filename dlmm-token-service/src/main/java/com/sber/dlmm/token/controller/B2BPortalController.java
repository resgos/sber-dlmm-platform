package com.sber.dlmm.token.controller;

import com.sber.dlmm.token.dto.B2BIssuerDtos.InvoiceResponse;
import com.sber.dlmm.token.dto.B2BIssuerDtos.IssuerResponse;
import com.sber.dlmm.token.dto.B2BIssuerDtos.RegisterIssuerRequest;
import com.sber.dlmm.token.dto.B2BIssuerDtos.RejectIssuerRequest;
import com.sber.dlmm.token.entity.B2BIssuer;
import com.sber.dlmm.token.service.B2BBillingService;
import com.sber.dlmm.token.service.B2BIssuerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "B2B Portal", description = "Sprint 5 #5.6 + #5.7 — corporate issuer registration, KYB review, invoices, and billing")
public class B2BPortalController {

    private final B2BIssuerService issuerService;
    private final B2BBillingService billingService;

    /**
     * Registers a corporate issuer in PENDING status for KYB review.
     * Idempotent by INN — re-registering an existing tax id returns the
     * existing issuer. Open to any authenticated caller (prototype).
     *
     * @param req validated registration body (INN, legal/display name, contacts, tier)
     * @return HTTP 201 with the {@link IssuerResponse} (new or pre-existing for the INN)
     */
    @PostMapping("/issuers")
    @Operation(summary = "Register a corp issuer for KYB review",
            description = "Creates a corporate issuer in PENDING status from the request body. Idempotent by INN "
                    + "(tax id) — re-registering an existing INN returns the existing issuer. Open to any authenticated "
                    + "caller as a prototype (production gates on a CORP_ADMIN role).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Issuer registered (or existing issuer for the INN)"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<IssuerResponse> register(@Valid @RequestBody RegisterIssuerRequest req) {
        B2BIssuer issuer = issuerService.register(req.inn(), req.legalName(),
                req.displayName(), req.contactEmail(), req.contactPhone(), req.tier());
        return ResponseEntity.status(HttpStatus.CREATED).body(IssuerResponse.from(issuer));
    }

    /**
     * Lists corporate issuers, optionally filtered by KYB status
     * (ADMIN / SUPER_ADMIN only).
     *
     * @param status optional KYB status filter; when null all issuers are returned
     * @return HTTP 200 with the matching {@link IssuerResponse} list (may be empty)
     */
    @GetMapping("/issuers")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List issuers, optionally filtered by KYB status",
            description = "Returns all corporate issuers, or only those in the given KYB status when the filter is "
                    + "provided. Restricted to ADMIN / SUPER_ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of issuers (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN / SUPER_ADMIN role")
    })
    public ResponseEntity<List<IssuerResponse>> list(
            @Parameter(description = "Optional KYB status filter (PENDING / APPROVED / REJECTED)")
            @RequestParam(required = false) B2BIssuer.KybStatus status) {
        List<B2BIssuer> all = status == null
                ? issuerService.findAll()
                : issuerService.findByStatus(status);
        return ResponseEntity.ok(all.stream().map(IssuerResponse::from).toList());
    }

    /**
     * Fetches a single corporate issuer by id (ADMIN / SUPER_ADMIN only).
     *
     * @param id issuer id from the path
     * @return HTTP 200 with the matching {@link IssuerResponse}
     */
    @GetMapping("/issuers/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get an issuer by id",
            description = "Returns a single corporate issuer by id. Restricted to ADMIN / SUPER_ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Issuer found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN / SUPER_ADMIN role")
    })
    public ResponseEntity<IssuerResponse> get(
            @Parameter(description = "Issuer id") @PathVariable UUID id) {
        return ResponseEntity.ok(IssuerResponse.from(issuerService.findById(id)));
    }

    /**
     * Approves an issuer's KYB, recording the reviewing admin; a no-op if
     * already approved (ADMIN / SUPER_ADMIN only).
     *
     * @param id   issuer id from the path
     * @param auth the authenticated principal; the reviewing admin's id is read from it
     * @return HTTP 200 with the updated {@link IssuerResponse}
     */
    @PostMapping("/issuers/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Approve KYB for issuer (ADMIN)",
            description = "Marks the issuer APPROVED and records the reviewing admin; a no-op if already approved. "
                    + "Restricted to ADMIN / SUPER_ADMIN. Returns the updated issuer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Issuer approved"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN / SUPER_ADMIN role")
    })
    public ResponseEntity<IssuerResponse> approve(
            @Parameter(description = "Issuer id") @PathVariable UUID id, Authentication auth) {
        UUID reviewer = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(IssuerResponse.from(issuerService.approve(id, reviewer)));
    }

    /**
     * Rejects an issuer's KYB with the supplied reason, recording the
     * reviewing admin (ADMIN / SUPER_ADMIN only).
     *
     * @param id   issuer id from the path
     * @param req  validated body carrying the rejection reason
     * @param auth the authenticated principal; the reviewing admin's id is read from it
     * @return HTTP 200 with the updated {@link IssuerResponse}
     */
    @PostMapping("/issuers/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Reject KYB for issuer (ADMIN)",
            description = "Marks the issuer REJECTED with the supplied reason and records the reviewing admin. "
                    + "Restricted to ADMIN / SUPER_ADMIN. Returns the updated issuer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Issuer rejected"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN / SUPER_ADMIN role")
    })
    public ResponseEntity<IssuerResponse> reject(
            @Parameter(description = "Issuer id") @PathVariable UUID id,
            @Valid @RequestBody RejectIssuerRequest req,
            Authentication auth) {
        UUID reviewer = (UUID) auth.getPrincipal();
        return ResponseEntity.ok(IssuerResponse.from(
                issuerService.reject(id, reviewer, req.reason())));
    }

    /**
     * Returns an issuer's billing invoice history, newest period first
     * (ADMIN / SUPER_ADMIN only).
     *
     * @param id issuer id from the path
     * @return HTTP 200 with the {@link InvoiceResponse} list (may be empty)
     */
    @GetMapping("/issuers/{id}/invoices")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Invoice history for an issuer",
            description = "Returns all billing invoices for an issuer, newest period first. "
                    + "Restricted to ADMIN / SUPER_ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice history (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN / SUPER_ADMIN role")
    })
    public ResponseEntity<List<InvoiceResponse>> invoices(
            @Parameter(description = "Issuer id") @PathVariable UUID id) {
        return ResponseEntity.ok(billingService.findByIssuer(id).stream()
                .map(InvoiceResponse::from).toList());
    }

    /**
     * Manual trigger for the monthly billing scheduler. Useful for demo
     * (don't want to wait until 1st of next month) and for re-running
     * the period if scheduler missed a fire. Idempotent — pre-existing
     * invoices for the period are skipped (ADMIN / SUPER_ADMIN only).
     *
     * @param period billing period as {@code YYYY-MM}; defaults to last month when omitted
     * @return HTTP 200 with an always-empty list body (the generated count is written to the log, not returned)
     */
    @PostMapping("/billing/run")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Manually trigger billing run for the given YYYY-MM period (ADMIN)",
            description = "Generates invoices for all APPROVED issuers for the given YYYY-MM period (defaults to last "
                    + "month when omitted); idempotent — issuers already billed for the period are skipped. Primarily "
                    + "for demo and dev. Restricted to ADMIN / SUPER_ADMIN. The response body is always empty — the "
                    + "generated count is written to the log.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Billing run executed (empty body)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN / SUPER_ADMIN role")
    })
    public ResponseEntity<List<InvoiceResponse>> runBilling(
            @Parameter(description = "Billing period as YYYY-MM; defaults to last month when omitted")
            @RequestParam(required = false) String period) {
        YearMonth ym = period != null ? YearMonth.parse(period) : YearMonth.now().minusMonths(1);
        int generated = billingService.generateInvoicesForPeriod(ym);
        return ResponseEntity.ok(
                java.util.List.of()  // empty body — generated count is in the log
        );
    }
}
