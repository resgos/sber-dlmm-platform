package com.sber.dlmm.token.controller;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.token.dto.BalanceResponse;
import com.sber.dlmm.token.dto.BurnRequest;
import com.sber.dlmm.token.dto.CreateTokenRequest;
import com.sber.dlmm.token.dto.MintRequest;
import com.sber.dlmm.token.dto.TokenResponse;
import com.sber.dlmm.token.dto.TransferRequest;
import com.sber.dlmm.token.service.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.UUID;

/**
 * REST API for the token catalog and user balances.
 *
 * <p>Exposes three groups of endpoints under {@code /api/v1}:
 * <ul>
 *   <li><b>Catalog</b> — create / list / look-up tokens (by id, symbol, or
 *       batch) and pause/unpause them. Mutations are restricted to
 *       ADMIN / SUPER_ADMIN; reads are open to any authenticated caller.</li>
 *   <li><b>Supply &amp; transfer</b> — admin mint/burn and user-to-user
 *       transfer.</li>
 *   <li><b>Balances</b> — the caller's own balances ({@code /balances/me}),
 *       a specific user's balances (admin), and the internal
 *       deduct/credit endpoints consumed by pool-engine during swaps and
 *       liquidity operations.</li>
 * </ul>
 *
 * <p>Each handler delegates to {@link TokenService}; the acting user id is
 * always taken from the JWT {@link Authentication}, never from the request
 * body. Token amounts throughout are raw integers on the uniform ×10⁴
 * platform scale.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Token Catalog & Balances", description = "Token catalog CRUD, mint/burn/transfer, user balances, and internal balance debit/credit")
public class TokenController {

    private final TokenService tokenService;

    /**
     * Creates the controller.
     *
     * @param tokenService service implementing all token catalog, supply and balance operations
     */
    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Registers a new token in the catalog (ADMIN / SUPER_ADMIN only).
     *
     * @param request        validated token definition (name, symbol, supply, type, flags)
     * @param authentication the authenticated principal; the creating admin's id is read from it
     * @return HTTP 201 with the created {@link TokenResponse}
     */
    @PostMapping("/tokens")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Create a new token",
            description = "Registers a new token in the catalog from the request body and records the creating admin. "
                    + "Restricted to ADMIN / SUPER_ADMIN. Returns the created token.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Token created"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN / SUPER_ADMIN role")
    })
    public ResponseEntity<TokenResponse> createToken(@Valid @RequestBody CreateTokenRequest request,
                                                     Authentication authentication) {
        UUID adminUserId = (UUID) authentication.getPrincipal();
        TokenResponse response = tokenService.createToken(request, adminUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Lists tokens as a page ordered newest-first. Open to any authenticated caller.
     *
     * @param page zero-based page index (defaults to 0)
     * @param size page size (defaults to 20)
     * @return HTTP 200 with a {@link PageResponse} of {@link TokenResponse}
     */
    @GetMapping("/tokens")
    @Operation(summary = "List all tokens",
            description = "Returns a page of tokens ordered by creation time (newest first). "
                    + "Open to any authenticated caller.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of tokens")
    })
    public ResponseEntity<PageResponse<TokenResponse>> getAllTokens(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size) {
        PageResponse<TokenResponse> response = tokenService.getAllTokens(page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * Looks up a single token by id. Open to any authenticated caller.
     *
     * @param id token id from the path
     * @return HTTP 200 with the matching {@link TokenResponse}, or 404 if none
     */
    @GetMapping("/tokens/{id}")
    @Operation(summary = "Get a token by id",
            description = "Returns the token with the given id. Open to any authenticated caller.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token found"),
            @ApiResponse(responseCode = "404", description = "No token with the given id")
    })
    public ResponseEntity<TokenResponse> getToken(
            @Parameter(description = "Token id") @PathVariable UUID id) {
        TokenResponse response = tokenService.getToken(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Bulk lookup endpoint consumed by pool-engine to avoid N+1 in /pools
     * symbol resolution. {@code ?ids=uuid1,uuid2,...} — comma-separated.
     * Returns a flat list; callers key by id.
     *
     * @param ids comma-separated token ids to resolve (unknown ids are omitted; empty input yields an empty list)
     * @return HTTP 200 with the matching tokens (order undefined; may be empty)
     */
    @GetMapping("/tokens/batch")
    @Operation(summary = "Bulk-lookup tokens by id",
            description = "Resolves several tokens in one round-trip; consumed by pool-engine to avoid N+1 symbol "
                    + "lookups when listing pools. Returns a flat list (order undefined, callers key by id); unknown "
                    + "ids are silently omitted and an empty list is returned for an empty input.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of matching tokens (may be empty)")
    })
    public ResponseEntity<java.util.List<TokenResponse>> getTokensByIds(
            @Parameter(description = "Comma-separated token ids, e.g. ?ids=uuid1,uuid2")
            @RequestParam("ids") java.util.List<UUID> ids) {
        return ResponseEntity.ok(tokenService.getTokensByIds(ids));
    }

    /**
     * Looks up a single token by its ticker symbol. Open to any authenticated caller.
     *
     * @param symbol token ticker symbol from the path (e.g. {@code SRUB})
     * @return HTTP 200 with the matching {@link TokenResponse}, or 404 if none
     */
    @GetMapping("/tokens/symbol/{symbol}")
    @Operation(summary = "Get a token by symbol",
            description = "Returns the token with the given ticker symbol (e.g. SRUB). Open to any authenticated caller.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token found"),
            @ApiResponse(responseCode = "404", description = "No token with the given symbol")
    })
    public ResponseEntity<TokenResponse> getTokenBySymbol(
            @Parameter(description = "Token ticker symbol") @PathVariable String symbol) {
        TokenResponse response = tokenService.getTokenBySymbol(symbol);
        return ResponseEntity.ok(response);
    }

    /**
     * Mints new supply to a user, increasing total supply (ADMIN / SUPER_ADMIN only).
     *
     * @param request        validated mint request (token, recipient, amount raw ×10⁴)
     * @param authentication the authenticated principal; the acting admin's id is read from it
     * @return HTTP 200 with the recipient's updated {@link BalanceResponse}
     */
    @PostMapping("/tokens/mint")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Mint token supply to a user",
            description = "Increases total supply and credits the minted amount to the target user's balance. "
                    + "Restricted to ADMIN / SUPER_ADMIN. Returns the recipient's updated balance.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Minted; updated recipient balance returned"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN / SUPER_ADMIN role"),
            @ApiResponse(responseCode = "404", description = "Token not found")
    })
    public ResponseEntity<BalanceResponse> mint(@Valid @RequestBody MintRequest request,
                                                Authentication authentication) {
        UUID adminUserId = (UUID) authentication.getPrincipal();
        BalanceResponse response = tokenService.mint(request, adminUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Burns supply from a user, decreasing total supply (ADMIN / SUPER_ADMIN only).
     * Fails if the user's available balance is insufficient.
     *
     * @param request        validated burn request (token, source user, amount raw ×10⁴)
     * @param authentication the authenticated principal; the acting admin's id is read from it
     * @return HTTP 200 with the source user's updated {@link BalanceResponse}
     */
    @PostMapping("/tokens/burn")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Burn token supply from a user",
            description = "Deducts the amount from the source user's available balance and decreases total supply. "
                    + "Restricted to ADMIN / SUPER_ADMIN. Returns the source user's updated balance.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Burned; updated source balance returned"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or insufficient available balance"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN / SUPER_ADMIN role"),
            @ApiResponse(responseCode = "404", description = "Token not found")
    })
    public ResponseEntity<BalanceResponse> burn(@Valid @RequestBody BurnRequest request,
                                                Authentication authentication) {
        UUID adminUserId = (UUID) authentication.getPrincipal();
        BalanceResponse response = tokenService.burn(request, adminUserId);
        return ResponseEntity.ok(response);
    }

    /**
     * Pauses a token, marking it inactive so mint/transfer are blocked
     * (ADMIN / SUPER_ADMIN only).
     *
     * @param id token id from the path
     * @return HTTP 200 with the updated {@link TokenResponse}
     */
    @PostMapping("/tokens/{id}/pause")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Pause a token",
            description = "Marks the token inactive, blocking mint/transfer until unpaused. "
                    + "Restricted to ADMIN / SUPER_ADMIN. Returns the updated token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token paused"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN / SUPER_ADMIN role"),
            @ApiResponse(responseCode = "404", description = "Token not found")
    })
    public ResponseEntity<TokenResponse> pauseToken(
            @Parameter(description = "Token id") @PathVariable UUID id) {
        TokenResponse response = tokenService.pauseToken(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Reactivates a previously paused token, re-enabling mint/transfer
     * (ADMIN / SUPER_ADMIN only).
     *
     * @param id token id from the path
     * @return HTTP 200 with the updated {@link TokenResponse}
     */
    @PostMapping("/tokens/{id}/unpause")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Unpause a token",
            description = "Marks a previously paused token active again, re-enabling mint/transfer. "
                    + "Restricted to ADMIN / SUPER_ADMIN. Returns the updated token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token unpaused"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN / SUPER_ADMIN role"),
            @ApiResponse(responseCode = "404", description = "Token not found")
    })
    public ResponseEntity<TokenResponse> unpauseToken(
            @Parameter(description = "Token id") @PathVariable UUID id) {
        TokenResponse response = tokenService.unpauseToken(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Transfers tokens between two users in one transaction; optionally
     * idempotent via the request's {@code idempotencyKey}. Open to any
     * authenticated caller.
     *
     * @param request validated transfer request (from, to, token, amount raw ×10⁴, optional idempotency key)
     * @return HTTP 204 on success
     */
    @PostMapping("/tokens/transfer")
    @Operation(summary = "Transfer tokens between users",
            description = "Moves an amount of a token from one user to another in a single transaction; optionally "
                    + "idempotent via idempotencyKey. Open to any authenticated caller. Returns no content on success.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Transfer completed"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or insufficient available balance"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "404", description = "Token not found"),
            @ApiResponse(responseCode = "409", description = "Duplicate idempotencyKey already processed")
    })
    public ResponseEntity<Void> transfer(@Valid @RequestBody TransferRequest request) {
        tokenService.transfer(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns all token balances for the authenticated caller.
     *
     * @param authentication the authenticated principal; the caller's user id is read from it
     * @return HTTP 200 with the caller's {@link BalanceResponse} list (may be empty)
     */
    @GetMapping("/balances/me")
    @Operation(summary = "List my balances",
            description = "Returns all token balances for the authenticated caller (user id taken from the JWT).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Caller's balances (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<List<BalanceResponse>> getMyBalances(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        List<BalanceResponse> response = tokenService.getUserBalances(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the authenticated caller's balance for one token; a zero
     * balance is returned when the caller holds none.
     *
     * @param tokenId        token id from the path
     * @param authentication the authenticated principal; the caller's user id is read from it
     * @return HTTP 200 with the caller's {@link BalanceResponse} for that token
     */
    @GetMapping("/balances/me/{tokenId}")
    @Operation(summary = "Get my balance for one token",
            description = "Returns the authenticated caller's balance for a specific token (user id taken from the "
                    + "JWT); a zero balance is returned if the caller holds none.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Caller's balance for the token"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "404", description = "Token not found")
    })
    public ResponseEntity<BalanceResponse> getMyTokenBalance(
            @Parameter(description = "Token id") @PathVariable UUID tokenId,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        BalanceResponse response = tokenService.getUserTokenBalance(userId, tokenId);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns all token balances for an arbitrary user (ADMIN / SUPER_ADMIN only).
     *
     * @param userId user id from the path to inspect
     * @return HTTP 200 with that user's {@link BalanceResponse} list (may be empty)
     */
    @GetMapping("/balances/user/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List a user's balances (admin)",
            description = "Returns all token balances for an arbitrary user id. "
                    + "Restricted to ADMIN / SUPER_ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User's balances (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN / SUPER_ADMIN role")
    })
    public ResponseEntity<List<BalanceResponse>> getUserBalances(
            @Parameter(description = "User id to inspect") @PathVariable UUID userId) {
        List<BalanceResponse> response = tokenService.getUserBalances(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Internal endpoints consumed by pool-engine during swap / add-liquidity /
     * remove-liquidity. Any authenticated user can invoke them — pool-engine
     * forwards the caller's Bearer via BearerTokenForwardingFilter, so these
     * effectively run "on behalf of" the original user. The deduct path
     * enforces ownership via userId in the body matching balance owner.
     *
     * <p>This handler debits a user's available balance (swap input token,
     * add-liquidity deposit); it fails on insufficient available balance.
     *
     * @param req validated balance request (user, token, positive amount raw ×10⁴)
     * @return HTTP 204 on success
     */
    @PostMapping("/tokens/internal/deduct")
    @Operation(summary = "Internal: debit a user's balance",
            description = "Deducts an amount from a user's available balance. Consumed by pool-engine during swap "
                    + "(debit input token) and add-liquidity (debit deposits), running on behalf of the original "
                    + "user via forwarded Bearer. Requires authentication. Returns no content on success.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Balance debited"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or insufficient available balance"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "404", description = "Token not found")
    })
    public ResponseEntity<Void> deductInternal(@Valid @RequestBody InternalBalanceRequest req) {
        tokenService.deductInternal(req.userId(), req.tokenId(), req.amount());
        return ResponseEntity.noContent().build();
    }

    /**
     * Internal counterpart to {@link #deductInternal}: credits a user's
     * available balance (swap output token, remove-liquidity return),
     * creating the balance row if absent. Consumed by pool-engine on behalf
     * of the original user via forwarded Bearer.
     *
     * @param req validated balance request (user, token, positive amount raw ×10⁴)
     * @return HTTP 204 on success
     */
    @PostMapping("/tokens/internal/credit")
    @Operation(summary = "Internal: credit a user's balance",
            description = "Credits an amount to a user's available balance, creating the balance row if absent. "
                    + "Consumed by pool-engine during swap (credit output token) and remove-liquidity (return "
                    + "withdrawn tokens). Requires authentication. Returns no content on success.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Balance credited"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "404", description = "Token not found")
    })
    public ResponseEntity<Void> creditInternal(@Valid @RequestBody InternalBalanceRequest req) {
        tokenService.creditInternal(req.userId(), req.tokenId(), req.amount());
        return ResponseEntity.noContent().build();
    }

    /**
     * Request body for the internal {@code deduct}/{@code credit} endpoints.
     *
     * @param userId  user whose balance is debited or credited (required)
     * @param tokenId token the balance is denominated in (required)
     * @param amount  quantity to move, raw integer on the ×10⁴ platform scale (must be &gt; 0)
     */
    public record InternalBalanceRequest(
            @jakarta.validation.constraints.NotNull UUID userId,
            @jakarta.validation.constraints.NotNull UUID tokenId,
            @jakarta.validation.constraints.Positive long amount
    ) {}
}
