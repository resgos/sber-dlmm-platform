package com.sber.dlmm.admin.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sber.dlmm.admin.client.BffProxyClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sprint 13 S13-01 — every downstream call now goes through
 * {@link BffProxyClient} which adds {@code @CircuitBreaker} +
 * {@code @Retry}. The previous implementation held five raw
 * {@link org.springframework.web.reactive.function.client.WebClient}
 * fields with inline {@code .onErrorReturn(...)} per call — that
 * caught HTTP errors but left threads pinned on slow downstreams for
 * the full 10s timeout. With CB-OPEN, the bff fails fast and frees its
 * threads, keeping the admin UI responsive even during a downstream
 * outage.
 *
 * <p>This controller now does only the things proxying can't move
 * downstream: request-body shape translation (mint/burn), nested-object
 * flattening (pool detail), and HTTP-shape mapping (404 when pool absent).
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin Proxy", description = "Admin proxy endpoints forwarding to downstream microservices")
public class AdminProxyController {

    private final BffProxyClient proxy;
    private final ObjectMapper objectMapper;

    /**
     * Creates the proxy controller with its collaborators injected by Spring.
     *
     * @param proxy        Resilience4j-wrapped client that performs every
     *                     downstream call (circuit breaker + retry) and returns
     *                     the raw JSON response (or a fallback) as a String
     * @param objectMapper Jackson mapper used for the two request-body
     *                     translations (mint/burn) and the pool-detail flatten
     */
    public AdminProxyController(BffProxyClient proxy, ObjectMapper objectMapper) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
    }

    // ============ USERS ============

    /**
     * Forwards a paginated, optionally filtered user listing to user-service.
     *
     * <p>Circuit-breaker guarded; on a user-service outage the breaker fallback
     * yields an empty page so the admin UI stays responsive.
     *
     * @param page zero-based page index
     * @param size page size (number of users per page)
     * @param query optional free-text filter (matched against email/name);
     *              may be {@code null}
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw user-page JSON ({@code {}} if absent)
     */
    @GetMapping("/users")
    @Operation(summary = "List users (proxy to user-service)",
            description = "Admin backend-for-frontend endpoint that forwards a paginated, optionally filtered "
                    + "user listing to user-service. The call is circuit-breaker guarded; on a user-service "
                    + "outage the breaker fallback returns an empty page so the admin UI stays responsive.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User page forwarded (empty page if user-service is unavailable)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> getUsers(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Optional free-text search filter (email/name)") @RequestParam(required = false) String query,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getUsers(page, size, query, auth));
    }

    /**
     * Forwards a single-user lookup to user-service.
     *
     * <p>Circuit-breaker guarded; on a user-service outage the breaker fallback
     * yields an empty JSON object.
     *
     * @param id   user identifier (UUID) taken from the path
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw user JSON ({@code {}} if absent)
     */
    @GetMapping("/users/{id}")
    @Operation(summary = "Get user by id (proxy to user-service)",
            description = "Admin backend-for-frontend endpoint that forwards a single-user lookup to user-service. "
                    + "The call is circuit-breaker guarded; on a user-service outage the breaker fallback returns "
                    + "an empty JSON object.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User forwarded (empty object if user-service is unavailable)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> getUser(
            @Parameter(description = "User identifier (UUID)") @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getUser(id, auth));
    }

    /**
     * Forwards a KYC-status change to user-service, passing the request body
     * through unparsed.
     *
     * <p>Circuit-breaker guarded; on a user-service outage the breaker fallback
     * yields an empty JSON object.
     *
     * @param id   user identifier (UUID) taken from the path
     * @param body raw JSON KYC-update payload, forwarded verbatim to user-service
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw user-service response ({@code {}} on fallback)
     */
    @PutMapping("/users/{id}/kyc")
    @Operation(summary = "Update user KYC status (proxy to user-service)",
            description = "Admin backend-for-frontend endpoint that forwards a KYC-status change to user-service. "
                    + "The raw JSON body is passed through unparsed. The call is circuit-breaker guarded; on a "
                    + "user-service outage the breaker fallback returns an empty JSON object.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "KYC update forwarded to user-service"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> updateKyc(
            @Parameter(description = "User identifier (UUID)") @PathVariable UUID id,
            @RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.updateKyc(id, body, auth));
    }

    /**
     * Forwards a role change to user-service, passing the request body through
     * unparsed.
     *
     * <p>Circuit-breaker guarded; on a user-service outage the breaker fallback
     * yields an empty JSON object.
     *
     * @param id   user identifier (UUID) taken from the path
     * @param body raw JSON role-update payload, forwarded verbatim to user-service
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw user-service response ({@code {}} on fallback)
     */
    @PutMapping("/users/{id}/role")
    @Operation(summary = "Update user role (proxy to user-service)",
            description = "Admin backend-for-frontend endpoint that forwards a role change to user-service. "
                    + "The raw JSON body is passed through unparsed. The call is circuit-breaker guarded; on a "
                    + "user-service outage the breaker fallback returns an empty JSON object.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role update forwarded to user-service"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> updateRole(
            @Parameter(description = "User identifier (UUID)") @PathVariable UUID id,
            @RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.updateRole(id, body, auth));
    }

    /**
     * Forwards a block action to user-service and replies {@code 204 No Content}.
     *
     * <p>Circuit-breaker guarded; on a user-service outage the breaker fallback
     * silently no-ops (the response is still {@code 204}).
     *
     * @param id   user identifier (UUID) taken from the path
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 204 No Content}
     */
    @PostMapping("/users/{id}/block")
    @Operation(summary = "Block user (proxy to user-service)",
            description = "Admin backend-for-frontend endpoint that forwards a block action to user-service and "
                    + "returns 204 No Content. The call is circuit-breaker guarded; on a user-service outage the "
                    + "breaker fallback silently no-ops (still 204).")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Block action forwarded to user-service"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<Void> blockUser(
            @Parameter(description = "User identifier (UUID)") @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        proxy.blockUser(id, auth);
        return ResponseEntity.noContent().build();
    }

    /**
     * Forwards an unblock action to user-service and replies {@code 204 No Content}.
     *
     * <p>Circuit-breaker guarded; on a user-service outage the breaker fallback
     * silently no-ops (the response is still {@code 204}).
     *
     * @param id   user identifier (UUID) taken from the path
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 204 No Content}
     */
    @PostMapping("/users/{id}/unblock")
    @Operation(summary = "Unblock user (proxy to user-service)",
            description = "Admin backend-for-frontend endpoint that forwards an unblock action to user-service and "
                    + "returns 204 No Content. The call is circuit-breaker guarded; on a user-service outage the "
                    + "breaker fallback silently no-ops (still 204).")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Unblock action forwarded to user-service"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<Void> unblockUser(
            @Parameter(description = "User identifier (UUID)") @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        proxy.unblockUser(id, auth);
        return ResponseEntity.noContent().build();
    }

    /**
     * Forwards a paginated per-user transaction listing to transaction-service.
     *
     * <p>Circuit-breaker guarded; on a transaction-service outage the breaker
     * fallback yields an empty page.
     *
     * @param id   user identifier (UUID) taken from the path
     * @param page zero-based page index
     * @param size page size (number of transactions per page)
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw transaction-page JSON ({@code {}} if absent)
     */
    @GetMapping("/users/{id}/transactions")
    @Operation(summary = "Get user transactions (proxy to transaction-service)",
            description = "Admin backend-for-frontend endpoint that forwards a paginated per-user transaction "
                    + "listing to transaction-service. The call is circuit-breaker guarded; on a "
                    + "transaction-service outage the breaker fallback returns an empty page.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction page forwarded (empty page if transaction-service is unavailable)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> getUserTransactions(
            @Parameter(description = "User identifier (UUID)") @PathVariable UUID id,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getUserTransactions(id, page, size, auth));
    }

    // ============ POOLS ============

    /**
     * Forwards a paginated pool listing to pool-engine.
     *
     * <p>Circuit-breaker guarded; on a pool-engine outage the breaker fallback
     * yields an empty page.
     *
     * @param page zero-based page index
     * @param size page size (number of pools per page)
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw pool-page JSON ({@code {}} if absent)
     */
    @GetMapping("/pools")
    @Operation(summary = "List pools (proxy to pool-engine)",
            description = "Admin backend-for-frontend endpoint that forwards a paginated pool listing to "
                    + "pool-engine. The call is circuit-breaker guarded; on a pool-engine outage the breaker "
                    + "fallback returns an empty page.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pool page forwarded (empty page if pool-engine is unavailable)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> getPools(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getPools(page, size, auth));
    }

    /**
     * Fetches a pool detail from pool-engine and flattens the nested
     * {@code {pool:{...}, bins:[...]}} response into a single flat object for the
     * admin UI (the inner {@code pool} fields are merged up to the top level and
     * sibling keys such as {@code bins} are preserved alongside them).
     *
     * <p>Circuit-breaker guarded. The underlying client returns {@code null} both
     * when the pool is absent and when the breaker is open, so this method maps a
     * {@code null} to {@code 404 Not Found}. If the flatten transformation throws,
     * the raw downstream JSON is returned unchanged.
     *
     * @param id   pool identifier (UUID) taken from the path
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the flattened pool-detail JSON, or
     *         {@code 404 Not Found} when the pool is absent / pool-engine is down
     */
    @GetMapping("/pools/{id}")
    @Operation(summary = "Get pool detail (proxy + flatten nested pool object)",
            description = "Admin backend-for-frontend endpoint that fetches a pool detail from pool-engine and "
                    + "flattens the nested {pool:{...}, bins:[...]} shape into a single object for the admin UI. "
                    + "The call is circuit-breaker guarded; if pool-engine returns nothing (absent pool or "
                    + "breaker fallback) the endpoint responds 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Flattened pool detail forwarded"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role"),
            @ApiResponse(responseCode = "404", description = "Pool not found, or pool-engine unavailable (circuit breaker open)")
    })
    public ResponseEntity<String> getPool(
            @Parameter(description = "Pool identifier (UUID)") @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        // BffProxyClient.getPool returns null on CB-OPEN or absence;
        // the controller maps that to 404 — same shape as before the
        // Resilience4j wrap.
        String raw = proxy.getPool(id, auth);
        if (raw == null) return ResponseEntity.notFound().build();
        // Flatten: {pool: {...}, bins: [...], ...} → {...pool, bins: [...], ...}
        try {
            Map<String, Object> nested = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
            Object poolObj = nested.get("pool");
            if (poolObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> poolMap = (Map<String, Object>) poolObj;
                Map<String, Object> flat = new HashMap<>(poolMap);
                nested.forEach((k, v) -> {
                    if (!"pool".equals(k)) flat.put(k, v);
                });
                return jsonOk(objectMapper.writeValueAsString(flat));
            }
        } catch (Exception ignored) {
            // return raw if transformation fails
        }
        return jsonOk(raw);
    }

    /**
     * Forwards a pool-creation request to pool-engine, passing the request body
     * through unparsed (pool-engine performs validation).
     *
     * <p>Circuit-breaker guarded; on a pool-engine outage the breaker fallback
     * returns a canned error JSON object.
     *
     * @param body raw JSON pool-creation payload, forwarded verbatim to pool-engine
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw pool-engine response (canned error JSON on fallback)
     */
    @PostMapping("/pools")
    @Operation(summary = "Create pool (proxy to pool-engine)",
            description = "Admin backend-for-frontend endpoint that forwards a pool-creation request to "
                    + "pool-engine. The raw JSON body is passed through unparsed; pool-engine performs validation. "
                    + "The call is circuit-breaker guarded; on a pool-engine outage the breaker fallback returns a "
                    + "canned error JSON object.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pool-creation request forwarded to pool-engine"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> createPool(
            @RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.createPool(body, auth));
    }

    /**
     * Forwards a pause action to pool-engine (temporarily halts the pool).
     *
     * <p>Circuit-breaker guarded; on a pool-engine outage the breaker fallback
     * yields an empty JSON object.
     *
     * @param id   pool identifier (UUID) taken from the path
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw pool-engine response ({@code {}} on fallback)
     */
    @PostMapping("/pools/{id}/pause")
    @Operation(summary = "Pause pool (proxy to pool-engine)",
            description = "Admin backend-for-frontend endpoint that forwards a pause action to pool-engine. "
                    + "The call is circuit-breaker guarded; on a pool-engine outage the breaker fallback returns "
                    + "an empty JSON object.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pause action forwarded to pool-engine"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> pausePool(
            @Parameter(description = "Pool identifier (UUID)") @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.pausePool(id, auth));
    }

    /**
     * Forwards a resume action to pool-engine (reactivates a paused pool).
     *
     * <p>Circuit-breaker guarded; on a pool-engine outage the breaker fallback
     * yields an empty JSON object.
     *
     * @param id   pool identifier (UUID) taken from the path
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw pool-engine response ({@code {}} on fallback)
     */
    @PostMapping("/pools/{id}/resume")
    @Operation(summary = "Resume pool (proxy to pool-engine)",
            description = "Admin backend-for-frontend endpoint that forwards a resume action to pool-engine. "
                    + "The call is circuit-breaker guarded; on a pool-engine outage the breaker fallback returns "
                    + "an empty JSON object.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resume action forwarded to pool-engine"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> resumePool(
            @Parameter(description = "Pool identifier (UUID)") @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.resumePool(id, auth));
    }

    /**
     * Forwards an emergency-shutdown action to pool-engine — the hard kill-switch
     * for a pool, distinct from a reversible pause.
     *
     * <p>Circuit-breaker guarded; on a pool-engine outage the breaker fallback
     * yields an empty JSON object.
     *
     * @param id   pool identifier (UUID) taken from the path
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw pool-engine response ({@code {}} on fallback)
     */
    @PostMapping("/pools/{id}/emergency-shutdown")
    @Operation(summary = "Emergency shutdown pool (proxy to pool-engine)",
            description = "Admin backend-for-frontend endpoint that forwards an emergency-shutdown action to "
                    + "pool-engine. The call is circuit-breaker guarded; on a pool-engine outage the breaker "
                    + "fallback returns an empty JSON object.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Emergency-shutdown action forwarded to pool-engine"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> emergencyShutdown(
            @Parameter(description = "Pool identifier (UUID)") @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.emergencyShutdown(id, auth));
    }

    // ============ TOKENS ============

    /**
     * Forwards a paginated token listing to token-service.
     *
     * <p>Circuit-breaker guarded; on a token-service outage the breaker fallback
     * yields an empty page.
     *
     * @param page zero-based page index
     * @param size page size (number of tokens per page)
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw token-page JSON ({@code {}} if absent)
     */
    @GetMapping("/tokens")
    @Operation(summary = "List tokens (proxy to token-service)",
            description = "Admin backend-for-frontend endpoint that forwards a paginated token listing to "
                    + "token-service. The call is circuit-breaker guarded; on a token-service outage the breaker "
                    + "fallback returns an empty page.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token page forwarded (empty page if token-service is unavailable)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> getTokens(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getTokens(page, size, auth));
    }

    /**
     * Forwards a single-token lookup to token-service.
     *
     * <p>Circuit-breaker guarded; on a token-service outage the breaker fallback
     * yields an empty JSON object.
     *
     * @param id   token identifier (UUID) taken from the path
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw token JSON ({@code {}} if absent)
     */
    @GetMapping("/tokens/{id}")
    @Operation(summary = "Get token (proxy to token-service)",
            description = "Admin backend-for-frontend endpoint that forwards a single-token lookup to "
                    + "token-service. The call is circuit-breaker guarded; on a token-service outage the breaker "
                    + "fallback returns an empty JSON object.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token forwarded (empty object if token-service is unavailable)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> getToken(
            @Parameter(description = "Token identifier (UUID)") @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getToken(id, auth));
    }

    /**
     * Forwards a token-creation request to token-service, passing the request
     * body through unparsed (token-service performs validation).
     *
     * <p>Circuit-breaker guarded; on a token-service outage the breaker fallback
     * returns a canned error JSON object.
     *
     * @param body raw JSON token-creation payload, forwarded verbatim to token-service
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw token-service response (canned error JSON on fallback)
     */
    @PostMapping("/tokens")
    @Operation(summary = "Create token (proxy to token-service)",
            description = "Admin backend-for-frontend endpoint that forwards a token-creation request to "
                    + "token-service. The raw JSON body is passed through unparsed; token-service performs "
                    + "validation. The call is circuit-breaker guarded; on a token-service outage the breaker "
                    + "fallback returns a canned error JSON object.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token-creation request forwarded to token-service"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> createToken(
            @RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.createToken(body, auth));
    }

    /**
     * Mints tokens via token-service, translating the admin-UI request body into
     * the downstream {@code MintRequest} shape before forwarding: the path id
     * becomes {@code tokenId}, {@code body.userId} becomes {@code toUserId}, and
     * {@code body.amount} is passed through as {@code amount} (a raw integer where
     * 1 unit = 10⁻⁴ token).
     *
     * <p>The downstream call is circuit-breaker guarded; on a token-service outage
     * the breaker fallback returns a canned error JSON object. If the translated
     * body cannot be serialised for forwarding, the endpoint replies
     * {@code 500 Internal Server Error}.
     *
     * @param id   token identifier (UUID) taken from the path; used as {@code tokenId}
     * @param body admin-UI mint payload; {@code userId} (recipient) and
     *             {@code amount} (raw integer) are read from it
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the token-service response, or {@code 500} on a
     *         serialisation failure
     */
    @PostMapping("/tokens/{id}/mint")
    @Operation(summary = "Mint tokens (proxy to token-service, transforms request)",
            description = "Admin backend-for-frontend endpoint that mints tokens. It translates the admin-UI body "
                    + "({amount, userId}) into the token-service MintRequest shape ({tokenId, toUserId, amount}) "
                    + "and forwards it. The downstream call is circuit-breaker guarded; on a token-service outage "
                    + "the breaker fallback returns a canned error JSON object. Returns 500 if the request body "
                    + "cannot be serialised for forwarding.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mint request forwarded to token-service"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role"),
            @ApiResponse(responseCode = "500", description = "Request body could not be serialised for forwarding")
    })
    public ResponseEntity<String> mintToken(
            @Parameter(description = "Token identifier (UUID)") @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        // Frontend: {amount, userId} → Backend MintRequest: {tokenId, toUserId, amount}
        Map<String, Object> transformed = new HashMap<>();
        transformed.put("tokenId", id.toString());
        transformed.put("toUserId", body.get("userId"));
        transformed.put("amount", body.get("amount"));
        try {
            return jsonOk(proxy.mintToken(objectMapper.writeValueAsString(transformed), auth));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Burns tokens via token-service, translating the admin-UI request body into
     * the downstream {@code BurnRequest} shape before forwarding: the path id
     * becomes {@code tokenId}, {@code body.userId} becomes {@code fromUserId}, and
     * {@code body.amount} is passed through as {@code amount} (a raw integer where
     * 1 unit = 10⁻⁴ token).
     *
     * <p>The downstream call is circuit-breaker guarded; on a token-service outage
     * the breaker fallback returns a canned error JSON object. If the translated
     * body cannot be serialised for forwarding, the endpoint replies
     * {@code 500 Internal Server Error}.
     *
     * @param id   token identifier (UUID) taken from the path; used as {@code tokenId}
     * @param body admin-UI burn payload; {@code userId} (holder to debit) and
     *             {@code amount} (raw integer) are read from it
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the token-service response, or {@code 500} on a
     *         serialisation failure
     */
    @PostMapping("/tokens/{id}/burn")
    @Operation(summary = "Burn tokens (proxy to token-service, transforms request)",
            description = "Admin backend-for-frontend endpoint that burns tokens. It translates the admin-UI body "
                    + "({amount, userId}) into the token-service BurnRequest shape ({tokenId, fromUserId, amount}) "
                    + "and forwards it. The downstream call is circuit-breaker guarded; on a token-service outage "
                    + "the breaker fallback returns a canned error JSON object. Returns 500 if the request body "
                    + "cannot be serialised for forwarding.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Burn request forwarded to token-service"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role"),
            @ApiResponse(responseCode = "500", description = "Request body could not be serialised for forwarding")
    })
    public ResponseEntity<String> burnToken(
            @Parameter(description = "Token identifier (UUID)") @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        // Frontend: {amount, userId} → Backend BurnRequest: {tokenId, fromUserId, amount}
        Map<String, Object> transformed = new HashMap<>();
        transformed.put("tokenId", id.toString());
        transformed.put("fromUserId", body.get("userId"));
        transformed.put("amount", body.get("amount"));
        try {
            return jsonOk(proxy.burnToken(objectMapper.writeValueAsString(transformed), auth));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ============ TRANSACTIONS ============

    /**
     * Forwards a paginated, optionally filtered platform-wide transaction listing
     * to transaction-service.
     *
     * <p>Circuit-breaker guarded; on a transaction-service outage the breaker
     * fallback yields an empty page.
     *
     * @param page   zero-based page index
     * @param size   page size (number of transactions per page)
     * @param txType optional transaction-type filter, e.g. {@code SWAP},
     *               {@code ADD_LIQUIDITY}; may be {@code null}
     * @param status optional status filter, e.g. {@code SETTLED}, {@code PENDING};
     *               may be {@code null}
     * @param auth   inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the raw transaction-page JSON ({@code {}} if absent)
     */
    @GetMapping("/transactions")
    @Operation(summary = "List all transactions (proxy to transaction-service)",
            description = "Admin backend-for-frontend endpoint that forwards a paginated, optionally filtered "
                    + "platform-wide transaction listing to transaction-service. The call is circuit-breaker "
                    + "guarded; on a transaction-service outage the breaker fallback returns an empty page.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction page forwarded (empty page if transaction-service is unavailable)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> getTransactions(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Optional transaction-type filter, e.g. SWAP, ADD_LIQUIDITY") @RequestParam(required = false) String txType,
            @Parameter(description = "Optional status filter, e.g. SETTLED, PENDING") @RequestParam(required = false) String status,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getTransactions(page, size, txType, status, auth));
    }

    /**
     * Sprint 9-DS-r4 (P2-12) — admin "Mark reviewed" action for a
     * suspicious transaction. Forwards to transaction-service's
     * {@code POST /transactions/{id}/review} which stamps
     * reviewedAt/reviewedBy.
     *
     * <p>Stamping the row removes it from the suspicious-transactions feed
     * served by {@code AdminController#getSuspiciousTransactions}. Circuit-breaker
     * guarded; on a transaction-service outage the breaker fallback returns a
     * canned error JSON object.
     *
     * @param id   transaction identifier (UUID) taken from the path
     * @param auth inbound {@code Authorization} header, forwarded downstream
     * @return {@code 200 OK} with the transaction-service response (canned error JSON on fallback)
     */
    @PostMapping("/transactions/{id}/review")
    @Operation(summary = "Mark a transaction as reviewed (proxy to transaction-service)",
            description = "Admin backend-for-frontend endpoint that forwards a 'mark reviewed' action for a "
                    + "suspicious transaction to transaction-service, which stamps reviewedAt/reviewedBy and "
                    + "removes the row from the suspicious-transactions list. The call is circuit-breaker guarded; "
                    + "on a transaction-service outage the breaker fallback returns a canned error JSON object.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Review action forwarded to transaction-service"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<String> reviewTransaction(
            @Parameter(description = "Transaction identifier (UUID)") @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.reviewTransaction(id, auth));
    }

    // ============ HELPERS ============

    /**
     * Wraps a raw downstream JSON string in a {@code 200 OK} response with the
     * {@code application/json} content type. A {@code null} body (e.g. a
     * circuit-breaker fallback that produced nothing) is normalised to an empty
     * JSON object {@code "{}"} so the admin UI always receives valid JSON.
     *
     * @param body the raw JSON payload to return; {@code null} becomes {@code "{}"}
     * @return a {@code 200 OK} {@link ResponseEntity} with a JSON content type
     */
    private ResponseEntity<String> jsonOk(String body) {
        if (body == null) body = "{}";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
