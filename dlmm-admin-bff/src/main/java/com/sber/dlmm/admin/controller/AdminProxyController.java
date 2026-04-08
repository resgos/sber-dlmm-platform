package com.sber.dlmm.admin.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin Proxy", description = "Admin proxy endpoints forwarding to downstream microservices")
public class AdminProxyController {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String EMPTY_PAGE = "{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0}";

    private final WebClient userServiceClient;
    private final WebClient tokenServiceClient;
    private final WebClient poolEngineClient;
    private final WebClient transactionServiceClient;
    private final ObjectMapper objectMapper;

    public AdminProxyController(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${dlmm.services.user-service-url}") String userServiceUrl,
            @Value("${dlmm.services.token-service-url}") String tokenServiceUrl,
            @Value("${dlmm.services.pool-engine-url}") String poolEngineUrl,
            @Value("${dlmm.services.transaction-service-url}") String transactionServiceUrl
    ) {
        this.userServiceClient = webClientBuilder.clone().baseUrl(userServiceUrl).build();
        this.tokenServiceClient = webClientBuilder.clone().baseUrl(tokenServiceUrl).build();
        this.poolEngineClient = webClientBuilder.clone().baseUrl(poolEngineUrl).build();
        this.transactionServiceClient = webClientBuilder.clone().baseUrl(transactionServiceUrl).build();
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
        String result = userServiceClient.get()
                .uri(u -> {
                    var b = u.path("/api/v1/users")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (query != null && !query.isBlank()) b.queryParam("query", query);
                    return b.build();
                })
                .header("Authorization", auth != null ? auth : "")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn(EMPTY_PAGE)
                .block(TIMEOUT);
        return jsonOk(result);
    }

    @GetMapping("/users/{id}")
    @Operation(summary = "Get user by id (proxy to user-service)")
    public ResponseEntity<String> getUser(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String result = userServiceClient.get()
                .uri("/api/v1/users/{id}", id)
                .header("Authorization", auth != null ? auth : "")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn("{}")
                .block(TIMEOUT);
        return jsonOk(result);
    }

    @PutMapping("/users/{id}/kyc")
    @Operation(summary = "Update user KYC status (proxy to user-service)")
    public ResponseEntity<String> updateKyc(
            @PathVariable UUID id,
            @RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String result = userServiceClient.put()
                .uri("/api/v1/users/{id}/kyc", id)
                .header("Authorization", auth != null ? auth : "")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn("{}")
                .block(TIMEOUT);
        return jsonOk(result);
    }

    @PutMapping("/users/{id}/role")
    @Operation(summary = "Update user role (proxy to user-service)")
    public ResponseEntity<String> updateRole(
            @PathVariable UUID id,
            @RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String result = userServiceClient.put()
                .uri("/api/v1/users/{id}/role", id)
                .header("Authorization", auth != null ? auth : "")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn("{}")
                .block(TIMEOUT);
        return jsonOk(result);
    }

    @PostMapping("/users/{id}/block")
    @Operation(summary = "Block user (proxy to user-service)")
    public ResponseEntity<Void> blockUser(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        userServiceClient.post()
                .uri("/api/v1/users/{id}/block", id)
                .header("Authorization", auth != null ? auth : "")
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorReturn(null)
                .block(TIMEOUT);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/unblock")
    @Operation(summary = "Unblock user (proxy to user-service)")
    public ResponseEntity<Void> unblockUser(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        userServiceClient.post()
                .uri("/api/v1/users/{id}/unblock", id)
                .header("Authorization", auth != null ? auth : "")
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorReturn(null)
                .block(TIMEOUT);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{id}/transactions")
    @Operation(summary = "Get user transactions (proxy to transaction-service)")
    public ResponseEntity<String> getUserTransactions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String result = transactionServiceClient.get()
                .uri(u -> u.path("/api/v1/transactions/user/{userId}")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build(id))
                .header("Authorization", auth != null ? auth : "")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn(EMPTY_PAGE)
                .block(TIMEOUT);
        return jsonOk(result);
    }

    // ============ POOLS ============

    @GetMapping("/pools")
    @Operation(summary = "List pools (proxy to pool-engine)")
    public ResponseEntity<String> getPools(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String result = poolEngineClient.get()
                .uri(u -> u.path("/api/v1/pools")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build())
                .header("Authorization", auth != null ? auth : "")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn(EMPTY_PAGE)
                .block(TIMEOUT);
        return jsonOk(result);
    }

    @GetMapping("/pools/{id}")
    @Operation(summary = "Get pool detail (proxy + flatten nested pool object)")
    public ResponseEntity<String> getPool(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String raw = poolEngineClient.get()
                .uri("/api/v1/pools/{id}", id)
                .header("Authorization", auth != null ? auth : "")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn(null)
                .block(TIMEOUT);
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
        String result = poolEngineClient.post()
                .uri("/api/v1/pools")
                .header("Authorization", auth != null ? auth : "")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn("{\"error\":\"Failed to create pool\"}")
                .block(TIMEOUT);
        return jsonOk(result);
    }

    @PostMapping("/pools/{id}/pause")
    @Operation(summary = "Pause pool (proxy to pool-engine)")
    public ResponseEntity<String> pausePool(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String result = poolEngineClient.post()
                .uri("/api/v1/pools/{id}/pause", id)
                .header("Authorization", auth != null ? auth : "")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn("{}")
                .block(TIMEOUT);
        return jsonOk(result);
    }

    @PostMapping("/pools/{id}/resume")
    @Operation(summary = "Resume pool (proxy to pool-engine)")
    public ResponseEntity<String> resumePool(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String result = poolEngineClient.post()
                .uri("/api/v1/pools/{id}/resume", id)
                .header("Authorization", auth != null ? auth : "")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn("{}")
                .block(TIMEOUT);
        return jsonOk(result);
    }

    @PostMapping("/pools/{id}/emergency-shutdown")
    @Operation(summary = "Emergency shutdown pool (proxy to pool-engine)")
    public ResponseEntity<String> emergencyShutdown(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String result = poolEngineClient.post()
                .uri("/api/v1/pools/{id}/emergency-shutdown", id)
                .header("Authorization", auth != null ? auth : "")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn("{}")
                .block(TIMEOUT);
        return jsonOk(result);
    }

    // ============ TOKENS ============

    @GetMapping("/tokens")
    @Operation(summary = "List tokens (proxy to token-service)")
    public ResponseEntity<String> getTokens(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String result = tokenServiceClient.get()
                .uri(u -> u.path("/api/v1/tokens")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build())
                .header("Authorization", auth != null ? auth : "")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn(EMPTY_PAGE)
                .block(TIMEOUT);
        return jsonOk(result);
    }

    @GetMapping("/tokens/{id}")
    @Operation(summary = "Get token (proxy to token-service)")
    public ResponseEntity<String> getToken(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String result = tokenServiceClient.get()
                .uri("/api/v1/tokens/{id}", id)
                .header("Authorization", auth != null ? auth : "")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn("{}")
                .block(TIMEOUT);
        return jsonOk(result);
    }

    @PostMapping("/tokens")
    @Operation(summary = "Create token (proxy to token-service)")
    public ResponseEntity<String> createToken(
            @RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        String result = tokenServiceClient.post()
                .uri("/api/v1/tokens")
                .header("Authorization", auth != null ? auth : "")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn("{\"error\":\"Failed to create token\"}")
                .block(TIMEOUT);
        return jsonOk(result);
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
            String result = tokenServiceClient.post()
                    .uri("/api/v1/tokens/mint")
                    .header("Authorization", auth != null ? auth : "")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(objectMapper.writeValueAsString(transformed))
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorReturn("{\"error\":\"Mint failed\"}")
                    .block(TIMEOUT);
            return jsonOk(result);
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
            String result = tokenServiceClient.post()
                    .uri("/api/v1/tokens/burn")
                    .header("Authorization", auth != null ? auth : "")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(objectMapper.writeValueAsString(transformed))
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorReturn("{\"error\":\"Burn failed\"}")
                    .block(TIMEOUT);
            return jsonOk(result);
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
        String result = transactionServiceClient.get()
                .uri(u -> {
                    var b = u.path("/api/v1/transactions")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (txType != null) b.queryParam("type", txType);
                    if (status != null) b.queryParam("status", status);
                    return b.build();
                })
                .header("Authorization", auth != null ? auth : "")
                .retrieve()
                .bodyToMono(String.class)
                .onErrorReturn(EMPTY_PAGE)
                .block(TIMEOUT);
        return jsonOk(result);
    }

    // ============ HELPERS ============

    private ResponseEntity<String> jsonOk(String body) {
        if (body == null) body = "{}";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
