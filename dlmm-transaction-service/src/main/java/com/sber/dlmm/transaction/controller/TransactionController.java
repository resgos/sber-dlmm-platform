package com.sber.dlmm.transaction.controller;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.enums.TransactionStatus;
import com.sber.dlmm.common.enums.TransactionType;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.transaction.dto.TransactionResponse;
import com.sber.dlmm.transaction.entity.Transaction;
import com.sber.dlmm.transaction.service.TransactionService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<PageResponse<TransactionResponse>> getAllTransactions(
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(transactionService.getAllTransactions(type, status, page, size));
    }

    @GetMapping("/me")
    public ResponseEntity<PageResponse<TransactionResponse>> getMyTransactions(
            Authentication authentication,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(transactionService.getUserTransactions(userId, type, status, from, to, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> getTransaction(
            @PathVariable UUID id,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        TransactionResponse transaction = transactionService.getTransaction(id);

        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SUPER_ADMIN"));

        if (!transaction.userId().equals(userId) && !isAdmin) {
            throw new ForbiddenException("Access denied");
        }

        return ResponseEntity.ok(transaction);
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<PageResponse<TransactionResponse>> getUserTransactions(
            @PathVariable UUID userId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(transactionService.getUserTransactions(userId, type, status, null, null, page, size));
    }

    /**
     * Sprint 4 #4.4 — settlement-report CSV download for corp accountants.
     *
     * GET /api/v1/transactions/report?userId=...&from=...&to=...
     *
     * Returns confirmed swap history in spreadsheet-friendly format for
     * 1С / SAP reconciliation. Caller restrictions:
     *   - regular users can ONLY pull their own report (userId param
     *     ignored, falls back to JWT subject)
     *   - ADMIN / SUPER_ADMIN can pull any user's via userId param
     *
     * Streams up to 10k rows (capped in service). Anything bigger
     * should be paginated by date window — this endpoint is for
     * monthly / quarterly batches, not full audit dumps.
     */
    @GetMapping(value = "/report", produces = "text/csv")
    public void downloadReport(
            Authentication authentication,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            HttpServletResponse response) throws IOException {

        UUID callerId = (UUID) authentication.getPrincipal();
        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SUPER_ADMIN"));

        UUID target = (userId != null && isAdmin) ? userId : callerId;

        List<Transaction> rows = transactionService.findForReport(target, from, to);

        // Filename includes user prefix + date range so multiple
        // downloads don't overwrite each other in the accountant's folder.
        String filename = String.format("dlmm-transactions-%s-%s-to-%s.csv",
                target.toString().substring(0, 8),
                from != null ? from.toLocalDate() : "all",
                to != null ? to.toLocalDate() : "now");
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        try (PrintWriter w = response.getWriter()) {
            // Header — column order frozen for downstream parser stability.
            w.println("tx_id,type,status,pool_id,token_in_id,amount_in,token_out_id,amount_out,fee_amount,fee_rate,bins_crossed,idempotency_key,created_at,confirmed_at");
            DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
            for (Transaction t : rows) {
                w.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%d,%s,%s,%s%n",
                        t.getId(),
                        t.getTxType(),
                        t.getStatus(),
                        nullable(t.getPoolId()),
                        nullable(t.getTokenInId()),
                        nullable(t.getAmountIn()),
                        nullable(t.getTokenOutId()),
                        nullable(t.getAmountOut()),
                        t.getFeeAmount(),
                        nullable(t.getFeeRate()),
                        t.getBinsCrossed(),
                        csvEscape(t.getIdempotencyKey()),
                        t.getCreatedAt() != null ? t.getCreatedAt().format(iso) : "",
                        t.getConfirmedAt() != null ? t.getConfirmedAt().format(iso) : ""
                );
            }
        }
    }

    private static String nullable(Object v) { return v == null ? "" : v.toString(); }

    private static String csvEscape(String v) {
        if (v == null) return "";
        // Comma/quote/newline → wrap in quotes + double inner quotes.
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
