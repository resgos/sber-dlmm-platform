package com.sber.dlmm.admin.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sber.dlmm.admin.client.BffProxyClient;
import io.swagger.v3.oas.annotations.Operation;
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

    public AdminProxyController(BffProxyClient proxy, ObjectMapper objectMapper) {
        this.proxy = proxy;
        this.objectMapper = objectMapper;
    }

    // ============ USERS ============

    @GetMapping("/users")
    @Operation(summary = "List users (proxy to user-service)")
    public ResponseEntity<String> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getUsers(page, size, query, auth));
    }

    @GetMapping("/users/{id}")
    @Operation(summary = "Get user by id (proxy to user-service)")
    public ResponseEntity<String> getUser(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getUser(id, auth));
    }

    @PutMapping("/users/{id}/kyc")
    @Operation(summary = "Update user KYC status (proxy to user-service)")
    public ResponseEntity<String> updateKyc(
            @PathVariable UUID id,
            @RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.updateKyc(id, body, auth));
    }

    @PutMapping("/users/{id}/role")
    @Operation(summary = "Update user role (proxy to user-service)")
    public ResponseEntity<String> updateRole(
            @PathVariable UUID id,
            @RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.updateRole(id, body, auth));
    }

    @PostMapping("/users/{id}/block")
    @Operation(summary = "Block user (proxy to user-service)")
    public ResponseEntity<Void> blockUser(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        proxy.blockUser(id, auth);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/unblock")
    @Operation(summary = "Unblock user (proxy to user-service)")
    public ResponseEntity<Void> unblockUser(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        proxy.unblockUser(id, auth);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{id}/transactions")
    @Operation(summary = "Get user transactions (proxy to transaction-service)")
    public ResponseEntity<String> getUserTransactions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getUserTransactions(id, page, size, auth));
    }

    // ============ POOLS ============

    @GetMapping("/pools")
    @Operation(summary = "List pools (proxy to pool-engine)")
    public ResponseEntity<String> getPools(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getPools(page, size, auth));
    }

    @GetMapping("/pools/{id}")
    @Operation(summary = "Get pool detail (proxy + flatten nested pool object)")
    public ResponseEntity<String> getPool(
            @PathVariable UUID id,
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

    @PostMapping("/pools")
    @Operation(summary = "Create pool (proxy to pool-engine)")
    public ResponseEntity<String> createPool(
            @RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.createPool(body, auth));
    }

    @PostMapping("/pools/{id}/pause")
    @Operation(summary = "Pause pool (proxy to pool-engine)")
    public ResponseEntity<String> pausePool(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.pausePool(id, auth));
    }

    @PostMapping("/pools/{id}/resume")
    @Operation(summary = "Resume pool (proxy to pool-engine)")
    public ResponseEntity<String> resumePool(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.resumePool(id, auth));
    }

    @PostMapping("/pools/{id}/emergency-shutdown")
    @Operation(summary = "Emergency shutdown pool (proxy to pool-engine)")
    public ResponseEntity<String> emergencyShutdown(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.emergencyShutdown(id, auth));
    }

    // ============ TOKENS ============

    @GetMapping("/tokens")
    @Operation(summary = "List tokens (proxy to token-service)")
    public ResponseEntity<String> getTokens(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getTokens(page, size, auth));
    }

    @GetMapping("/tokens/{id}")
    @Operation(summary = "Get token (proxy to token-service)")
    public ResponseEntity<String> getToken(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getToken(id, auth));
    }

    @PostMapping("/tokens")
    @Operation(summary = "Create token (proxy to token-service)")
    public ResponseEntity<String> createToken(
            @RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.createToken(body, auth));
    }

    @PostMapping("/tokens/{id}/mint")
    @Operation(summary = "Mint tokens (proxy to token-service, transforms request)")
    public ResponseEntity<String> mintToken(
            @PathVariable UUID id,
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

    @PostMapping("/tokens/{id}/burn")
    @Operation(summary = "Burn tokens (proxy to token-service, transforms request)")
    public ResponseEntity<String> burnToken(
            @PathVariable UUID id,
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

    @GetMapping("/transactions")
    @Operation(summary = "List all transactions (proxy to transaction-service)")
    public ResponseEntity<String> getTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String txType,
            @RequestParam(required = false) String status,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.getTransactions(page, size, txType, status, auth));
    }

    /**
     * Sprint 9-DS-r4 (P2-12) — admin "Mark reviewed" action for a
     * suspicious transaction. Forwards to transaction-service's
     * {@code POST /transactions/{id}/review} which stamps
     * reviewedAt/reviewedBy.
     */
    @PostMapping("/transactions/{id}/review")
    @Operation(summary = "Mark a transaction as reviewed (proxy to transaction-service)")
    public ResponseEntity<String> reviewTransaction(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jsonOk(proxy.reviewTransaction(id, auth));
    }

    // ============ HELPERS ============

    private ResponseEntity<String> jsonOk(String body) {
        if (body == null) body = "{}";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
