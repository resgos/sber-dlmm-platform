package com.sber.dlmm.token.controller;

import com.sber.dlmm.token.entity.YsrubReserveMovement;
import com.sber.dlmm.token.repository.YsrubReserveMovementRepository;
import com.sber.dlmm.token.service.YsrubService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Sprint 9 #7.1 — Tokenized money market endpoints.
 *
 * <p>Three operations, all user-scoped:
 * <ul>
 *   <li>POST /ysrub/mint — deposit SRUB, receive YSRUB</li>
 *   <li>POST /ysrub/burn — burn YSRUB, withdraw SRUB</li>
 *   <li>GET /ysrub/me — own movement history (paged)</li>
 * </ul>
 *
 * <p>Auth: requires authenticated user (gateway JWT pass-through);
 * KYC check is currently relied-upon via the gateway X-Kyc-Status
 * header — Sprint 10 will add explicit service-layer KYC enforcement
 * mirroring pool-engine's pattern.
 */
@RestController
@RequestMapping("/api/v1/tokens/ysrub")
@RequiredArgsConstructor
@Tag(name = "YSRUB Money Market", description = "Sprint 9 #7.1 — tokenized money market mint/burn")
public class YsrubController {

    private final YsrubService ysrubService;
    private final YsrubReserveMovementRepository reserveRepository;

    @PostMapping("/mint")
    @Operation(summary = "Deposit SRUB, receive YSRUB at current ratio (1:1 in Sprint 9)")
    public ResponseEntity<YsrubMovementResponse> mint(Authentication auth,
                                                       @Valid @RequestBody YsrubOpRequest req) {
        UUID userId = parseUserId(auth);
        YsrubReserveMovement movement = ysrubService.mint(userId, req.srubAmount(), req.idempotencyKey());
        return ResponseEntity.ok(YsrubMovementResponse.from(movement));
    }

    @PostMapping("/burn")
    @Operation(summary = "Burn YSRUB, withdraw SRUB at current ratio (1:1 in Sprint 9)")
    public ResponseEntity<YsrubMovementResponse> burn(Authentication auth,
                                                       @Valid @RequestBody YsrubBurnRequest req) {
        UUID userId = parseUserId(auth);
        YsrubReserveMovement movement = ysrubService.burn(userId, req.ysrubAmount(), req.idempotencyKey());
        return ResponseEntity.ok(YsrubMovementResponse.from(movement));
    }

    @GetMapping("/me")
    @Operation(summary = "My YSRUB movement history (paged, latest first)")
    public ResponseEntity<List<YsrubMovementResponse>> myMovements(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = parseUserId(auth);
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        List<YsrubMovementResponse> rows = reserveRepository
                .findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .getContent().stream()
                .map(YsrubMovementResponse::from)
                .toList();
        return ResponseEntity.ok(rows);
    }

    private static UUID parseUserId(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof UUID uuid) return uuid;
        return UUID.fromString(principal.toString());
    }

    // ── DTOs (records — wire-compatible, no Lombok needed) ──

    public record YsrubOpRequest(
            @Positive(message = "srubAmount must be > 0") long srubAmount,
            @NotBlank(message = "idempotencyKey required") String idempotencyKey) {}

    public record YsrubBurnRequest(
            @Positive(message = "ysrubAmount must be > 0") long ysrubAmount,
            @NotBlank(message = "idempotencyKey required") String idempotencyKey) {}

    public record YsrubMovementResponse(
            UUID id,
            UUID userId,
            String direction,
            long srubAmount,
            long ysrubAmount,
            long ratioMicro,
            LocalDateTime createdAt
    ) {
        public static YsrubMovementResponse from(YsrubReserveMovement m) {
            return new YsrubMovementResponse(
                    m.getId(), m.getUserId(), m.getDirection().name(),
                    m.getSrubAmount(), m.getYsrubAmount(), m.getRatioMicro(),
                    m.getCreatedAt());
        }
    }
}
