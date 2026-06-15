package com.sber.dlmm.transaction.controller;

import com.sber.dlmm.common.audit.AdminAudit;
import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.enums.TransactionStatus;
import com.sber.dlmm.common.enums.TransactionType;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.transaction.dto.TransactionResponse;
import com.sber.dlmm.transaction.entity.Transaction;
import com.sber.dlmm.transaction.export.OneCExchangeFormatter;
import com.sber.dlmm.transaction.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/**
 * REST API over the transaction ledger.
 *
 * <p>Surfaces history queries (admin-wide, per-user, by-id, pool-scoped feed),
 * the AML "mark reviewed" action, and settlement-report download (CSV / 1C).
 * Thin layer: it enforces authorization (owner-or-admin / role gates) and
 * delegates all logic to {@link TransactionService}; the report endpoints add
 * file streaming via {@link OneCExchangeFormatter}.
 *
 * <p>Ownership rule: user-facing reads derive the user id from the JWT
 * ({@code authentication.getPrincipal()}), never from a request parameter, so
 * a caller can only ever see their own data unless they hold ADMIN /
 * SUPER_ADMIN.
 *
 * <p>Lombok {@code @RequiredArgsConstructor} injects {@link TransactionService}.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Transaction ledger: query history, AML review, and settlement-report export")
public class TransactionController {

    private final TransactionService transactionService;

    /**
     * Admin-wide transaction listing, optionally filtered by type/status.
     * Authorization is enforced by {@code @PreAuthorize} (ADMIN / SUPER_ADMIN).
     *
     * @param type   optional transaction-type filter
     * @param status optional status filter
     * @param page   zero-based page index (default 0)
     * @param size   page size (default 20)
     * @return 200 with a {@link PageResponse} of {@link TransactionResponse}
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
            summary = "List all transactions (admin)",
            description = "Returns a paginated, createdAt-DESC page of every user's transactions across the platform, "
                    + "optionally filtered by type and status. ADMIN / SUPER_ADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of transactions returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN / SUPER_ADMIN")
    })
    public ResponseEntity<PageResponse<TransactionResponse>> getAllTransactions(
            @Parameter(description = "Optional filter by transaction type") @RequestParam(required = false) TransactionType type,
            @Parameter(description = "Optional filter by transaction status") @RequestParam(required = false) TransactionStatus status,
            @Parameter(description = "Optional inclusive lower bound on createdAt (ISO-8601 local date-time)") @RequestParam(required = false) LocalDateTime from,
            @Parameter(description = "Optional inclusive upper bound on createdAt (ISO-8601 local date-time)") @RequestParam(required = false) LocalDateTime to,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(transactionService.getAllTransactions(type, status, from, to, page, size));
    }

    /**
     * Lists the authenticated caller's own transactions (user id taken from the
     * JWT), optionally filtered by type, status, and a created-at window.
     *
     * @param authentication the caller's authentication (principal = user id)
     * @param type           optional transaction-type filter
     * @param status         optional status filter
     * @param from           optional inclusive lower bound on createdAt
     * @param to             optional inclusive upper bound on createdAt
     * @param page           zero-based page index (default 0)
     * @param size           page size (default 20)
     * @return 200 with a {@link PageResponse} of the caller's transactions
     */
    @GetMapping("/me")
    @Operation(
            summary = "List the caller's own transactions",
            description = "Returns a paginated, createdAt-DESC page of the authenticated caller's transactions "
                    + "(user id taken from the JWT, never a request param), optionally filtered by type, status, "
                    + "and a created-at date window. Any authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of the caller's transactions returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
    })
    public ResponseEntity<PageResponse<TransactionResponse>> getMyTransactions(
            Authentication authentication,
            @Parameter(description = "Optional filter by transaction type") @RequestParam(required = false) TransactionType type,
            @Parameter(description = "Optional filter by transaction status") @RequestParam(required = false) TransactionStatus status,
            @Parameter(description = "Optional inclusive lower bound on createdAt (ISO-8601 local date-time)") @RequestParam(required = false) LocalDateTime from,
            @Parameter(description = "Optional inclusive upper bound on createdAt (ISO-8601 local date-time)") @RequestParam(required = false) LocalDateTime to,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        UUID userId = (UUID) authentication.getPrincipal();
        return ResponseEntity.ok(transactionService.getUserTransactions(userId, type, status, from, to, page, size));
    }

    /**
     * Fetches a single transaction by id, enforcing owner-or-admin access in
     * code (this endpoint has no {@code @PreAuthorize}; the check is manual
     * because the rule depends on the row's owner).
     *
     * @param id             transaction id to fetch
     * @param authentication the caller's authentication (principal = user id)
     * @return 200 with the {@link TransactionResponse}
     * @throws ForbiddenException if the caller neither owns the transaction nor
     *         holds ADMIN / SUPER_ADMIN
     */
    @GetMapping("/{id}")
    @Operation(
            summary = "Get a single transaction by id",
            description = "Returns one transaction. The caller must either own the transaction or be ADMIN / "
                    + "SUPER_ADMIN; otherwise access is denied.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is neither the owner nor an admin")
    })
    public ResponseEntity<TransactionResponse> getTransaction(
            @Parameter(description = "Transaction id") @PathVariable UUID id,
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

    /**
     * Admin lookup of a specific user's transactions, optionally filtered by
     * type/status. Authorization enforced by {@code @PreAuthorize}.
     *
     * @param userId user whose transactions to list
     * @param type   optional transaction-type filter
     * @param status optional status filter
     * @param page   zero-based page index (default 0)
     * @param size   page size (default 20)
     * @return 200 with a {@link PageResponse} of the user's transactions
     */
    @GetMapping("/user/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
            summary = "List a specific user's transactions (admin)",
            description = "Returns a paginated, createdAt-DESC page of the given user's transactions, optionally "
                    + "filtered by type and status. ADMIN / SUPER_ADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of the user's transactions returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN / SUPER_ADMIN")
    })
    public ResponseEntity<PageResponse<TransactionResponse>> getUserTransactions(
            @Parameter(description = "Id of the user whose transactions to list") @PathVariable UUID userId,
            @Parameter(description = "Optional filter by transaction type") @RequestParam(required = false) TransactionType type,
            @Parameter(description = "Optional filter by transaction status") @RequestParam(required = false) TransactionStatus status,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(transactionService.getUserTransactions(userId, type, status, null, null, page, size));
    }

    /**
     * Sprint 9-DS-r4 (P1-6) — Meteora-style pool-scoped recent
     * transactions feed. Backs the "История" panel below the bin
     * chart on user-ui PoolDetailPage. Public read (no @PreAuthorize)
     * because pool history is on-chain-equivalent data — no PII
     * leaks; userId in the payload is already exposed elsewhere
     * (top-LPs list, leaderboard).
     *
     * @param poolId pool whose recent swaps to return
     * @param limit  max rows; the service clamps it to [1, 100] (default 20)
     * @return 200 with a newest-first list of {@link TransactionResponse}
     */
    @GetMapping("/pool/{poolId}")
    @Operation(
            summary = "Recent swap feed for a pool",
            description = "Returns the most recent SWAP transactions for a pool, newest first, backing the "
                    + "Meteora-style history panel on the pool detail page. The limit is clamped to [1, 100]. "
                    + "Available to any authenticated user — the payload carries no PII beyond data already "
                    + "exposed elsewhere (top-LP lists, leaderboard).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of recent pool swaps returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
    })
    public ResponseEntity<List<TransactionResponse>> getRecentPoolTransactions(
            @Parameter(description = "Pool id") @PathVariable UUID poolId,
            @Parameter(description = "Max rows to return; clamped to [1, 100]") @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(transactionService.getRecentPoolTransactions(poolId, limit));
    }

    /**
     * Sprint 9-DS-r4 (P2-12) — admin "Mark reviewed" action for the
     * SuspiciousTransactionsPage. Stamps reviewedAt/reviewedBy on the
     * transaction so admin-bff's on-the-fly suspicious detection
     * stops re-surfacing it. ADMIN / SUPER_ADMIN only.
     *
     * @param id             transaction id to mark reviewed
     * @param authentication the caller's authentication (principal = reviewer id)
     * @return 200 with the updated {@link TransactionResponse}
     */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(
            summary = "Mark a transaction as AML-reviewed",
            description = "Stamps reviewedAt / reviewedBy on the transaction so the admin BFF's suspicious-activity "
                    + "detection stops re-surfacing it; idempotent (re-reviewing updates the reviewer to the latest "
                    + "caller). Returns the updated transaction. ADMIN / SUPER_ADMIN only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction marked reviewed and returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN / SUPER_ADMIN")
    })
    @AdminAudit(action = "TX_MARK_REVIEWED", targetType = "TX", targetIdParam = "id")
    public ResponseEntity<TransactionResponse> markReviewed(
            @Parameter(description = "Transaction id to mark reviewed") @PathVariable UUID id,
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
     *
     * @param authentication the caller's authentication (principal = caller id)
     * @param userId         target user id; honoured only for ADMIN /
     *                       SUPER_ADMIN, otherwise ignored in favour of the caller
     * @param from           optional inclusive lower bound on createdAt
     * @param to             optional inclusive upper bound on createdAt
     * @param format         output format: {@code csv} (default) or {@code 1c}/{@code 1с}
     * @param response       servlet response the report is streamed to
     * @throws IOException if writing the response stream fails
     */
    @GetMapping(value = "/report")
    @Operation(
            summary = "Download a settlement report (CSV or 1C)",
            description = "Streams the caller's transactions as a downloadable file over a created-at date window. "
                    + "format=csv (default) returns a 14-column UTF-8 CSV; format=1c (also '1с') returns a "
                    + "1CClientBankExchange v1.03 Windows-1251 text file for import into 1С Бухгалтерия. Regular "
                    + "users always get their own data (the userId param is ignored); only ADMIN / SUPER_ADMIN may "
                    + "pull another user's report via userId. Capped at 10k rows — use a tighter date window for more.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Report file streamed as an attachment"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid access token")
    })
    public void downloadReport(
            Authentication authentication,
            @Parameter(description = "Target user id; honoured only for ADMIN / SUPER_ADMIN, otherwise ignored in favour of the caller") @RequestParam(required = false) UUID userId,
            @Parameter(description = "Optional inclusive lower bound on createdAt (ISO-8601 local date-time)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Optional inclusive upper bound on createdAt (ISO-8601 local date-time)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "Output format: 'csv' (default) or '1c'/'1с'") @RequestParam(required = false, defaultValue = "csv") String format,
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

    /**
     * Writes the rows as a UTF-8 CSV attachment with a frozen 14-column header
     * (column order is contract for downstream ETL parsers). The filename
     * encodes the target user prefix and the date window.
     *
     * @param target   user the report is for (drives the filename prefix)
     * @param rows     transactions to write
     * @param from     window lower bound, or {@code null} ("all" in filename)
     * @param to       window upper bound, or {@code null} ("now" in filename)
     * @param response servlet response to stream the CSV to
     * @throws IOException if writing the response fails
     */
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
     *
     * <p>Delegates the actual document text to {@link OneCExchangeFormatter}
     * and writes the Windows-1251 bytes directly to the output stream,
     * bypassing the servlet's default UTF-8 writer.
     *
     * @param target   user the report is for (drives the filename prefix)
     * @param rows     transactions to write
     * @param from     window lower bound, or {@code null}
     * @param to       window upper bound, or {@code null}
     * @param response servlet response to stream the file to
     * @throws IOException if writing the response fails
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

    /**
     * Renders a possibly-null value as a CSV cell: empty string for null,
     * otherwise {@code toString()}.
     *
     * @param v value to render, possibly {@code null}
     * @return the empty string if {@code v} is null, else {@code v.toString()}
     */
    private static String nullable(Object v) { return v == null ? "" : v.toString(); }

    /**
     * Escapes a CSV field per RFC 4180: if it contains a comma, quote, or
     * newline, wrap it in double quotes and double any embedded quotes.
     *
     * @param v field value, possibly {@code null}
     * @return the escaped field (empty string for null)
     */
    private static String csvEscape(String v) {
        if (v == null) return "";
        // Comma/quote/newline → wrap in quotes + double inner quotes.
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
