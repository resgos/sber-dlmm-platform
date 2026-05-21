package com.sber.dlmm.transaction.controller;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.enums.TransactionStatus;
import com.sber.dlmm.common.enums.TransactionType;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.transaction.dto.TransactionResponse;
import com.sber.dlmm.transaction.entity.Transaction;
import com.sber.dlmm.transaction.export.OneCExchangeFormatter;
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
     * Sprint 9-DS-r4 (P1-6) — Meteora-style pool-scoped recent
     * transactions feed. Backs the "История" panel below the bin
     * chart on user-ui PoolDetailPage. Public read (no @PreAuthorize)
     * because pool history is on-chain-equivalent data — no PII
     * leaks; userId in the payload is already exposed elsewhere
     * (top-LPs list, leaderboard).
     */
    @GetMapping("/pool/{poolId}")
    public ResponseEntity<List<TransactionResponse>> getRecentPoolTransactions(
            @PathVariable UUID poolId,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(transactionService.getRecentPoolTransactions(poolId, limit));
    }

    /**
     * Sprint 9-DS-r4 (P2-12) — admin "Mark reviewed" action for the
     * SuspiciousTransactionsPage. Stamps reviewedAt/reviewedBy on the
     * transaction so admin-bff's on-the-fly suspicious detection
     * stops re-surfacing it. ADMIN / SUPER_ADMIN only.
     */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<TransactionResponse> markReviewed(
            @PathVariable UUID id,
            Authentication authentication) {
        UUID reviewer = (UUID) authentication.getPrincipal();
        Transaction reviewed = transactionService.markReviewed(id, reviewer);
        return ResponseEntity.ok(transactionService.getTransaction(reviewed.getId()));
    }

    /**
     * Sprint 4 #4.4 + Sprint 5 #5.11 — settlement-report download for
     * corp accountants. Two output formats:
     * <ul>
     *   <li><b>format=csv</b> (default) — spreadsheet-friendly CSV, 14
     *       columns, header frozen for ETL stability.</li>
     *   <li><b>format=1c</b> — 1CClientBankExchange v1.03 text format
     *       (Windows-1251), imports straight into 1С Бухгалтерия 8.3.
     *       Russian accountant lives in 1С; see #5.11 javadoc on
     *       {@link OneCExchangeFormatter} for the spec.</li>
     * </ul>
     *
     * GET /api/v1/transactions/report?userId=...&from=...&to=...&format=csv|1c
     *
     * Caller restrictions:
     *   - regular users can ONLY pull their own report (userId param
     *     ignored, falls back to JWT subject)
     *   - ADMIN / SUPER_ADMIN can pull any user's via userId param
     *
     * Streams up to 10k rows (capped in service). Anything bigger
     * should be paginated by date window — this endpoint is for
     * monthly / quarterly batches, not full audit dumps.
     */
    @GetMapping(value = "/report")
    public void downloadReport(
            Authentication authentication,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false, defaultValue = "csv") String format,
            HttpServletResponse response) throws IOException {

        UUID callerId = (UUID) authentication.getPrincipal();
        boolean isAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SUPER_ADMIN"));

        UUID target = (userId != null && isAdmin) ? userId : callerId;

        List<Transaction> rows = transactionService.findForReport(target, from, to);

        if ("1c".equalsIgnoreCase(format) || "1с".equalsIgnoreCase(format)) {
            writeOneCReport(target, rows, from, to, response);
        } else {
            writeCsvReport(target, rows, from, to, response);
        }
    }

    private static void writeCsvReport(UUID target,
                                        List<Transaction> rows,
                                        LocalDateTime from,
                                        LocalDateTime to,
                                        HttpServletResponse response) throws IOException {
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

    /**
     * Sprint 5 #5.11 — 1CClientBankExchange v1.03 export. Filename uses
     * .txt extension (1С doesn't require a specific extension; .txt is
     * convention). Charset is Windows-1251 — the spec mandates it, and
     * 1С import wizard reads the {@code Кодировка=Windows} header line
     * to pick the decoder.
     */
    private static void writeOneCReport(UUID target,
                                         List<Transaction> rows,
                                         LocalDateTime from,
                                         LocalDateTime to,
                                         HttpServletResponse response) throws IOException {
        String filename = String.format("dlmm-1c-%s-%s-to-%s.txt",
                target.toString().substring(0, 8),
                from != null ? from.toLocalDate() : "all",
                to != null ? to.toLocalDate() : "now");
        // Use text/plain because 1С doesn't define a custom mime;
        // charset=Windows-1251 per spec.
        response.setContentType("text/plain; charset=windows-1251");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");

        String body = OneCExchangeFormatter.format(
                target, rows,
                from != null ? from.toLocalDate() : null,
                to != null ? to.toLocalDate() : null,
                LocalDateTime.now());
        // Manual write of Windows-1251 bytes — bypass Servlet's default UTF-8.
        response.getOutputStream().write(body.getBytes(java.nio.charset.Charset.forName("Windows-1251")));
        response.getOutputStream().flush();
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
