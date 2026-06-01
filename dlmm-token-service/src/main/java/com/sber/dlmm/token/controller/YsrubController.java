package com.sber.dlmm.token.controller;

import com.sber.dlmm.token.entity.YsrubReserveMovement;
import com.sber.dlmm.token.repository.YsrubReserveMovementRepository;
import com.sber.dlmm.token.service.YsrubService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

    /**
     * Deposits SRUB and credits YSRUB at the current ratio. Idempotent by
     * {@code idempotencyKey} — a replay returns the original movement.
     *
     * @param auth the authenticated principal; the caller's user id is read from it
     * @param req  validated mint request (SRUB amount raw ×10⁴, idempotency key)
     * @return HTTP 200 with the resulting {@link YsrubMovementResponse}
     */
    @PostMapping("/mint")
    @Operation(summary = "Deposit SRUB, receive YSRUB at current ratio (1:1 in Sprint 9)",
            description = "Moves the requested SRUB from the caller's balance into the reserve and credits YSRUB at "
                    + "the current ratio (user id taken from the JWT). Idempotent by idempotencyKey — a replay returns "
                    + "the original movement. Requires an authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mint succeeded (or idempotent replay); movement returned"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or insufficient SRUB balance"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "404", description = "SRUB / YSRUB token row not found")
    })
    public ResponseEntity<YsrubMovementResponse> mint(Authentication auth,
                                                       @Valid @RequestBody YsrubOpRequest req) {
        UUID userId = parseUserId(auth);
        YsrubReserveMovement movement = ysrubService.mint(userId, req.srubAmount(), req.idempotencyKey());
        return ResponseEntity.ok(YsrubMovementResponse.from(movement));
    }

    /**
     * Burns YSRUB and returns the equivalent SRUB from the reserve at the
     * current ratio. Idempotent by {@code idempotencyKey} — a replay returns
     * the original movement.
     *
     * @param auth the authenticated principal; the caller's user id is read from it
     * @param req  validated burn request (YSRUB amount raw ×10⁴, idempotency key)
     * @return HTTP 200 with the resulting {@link YsrubMovementResponse}
     */
    @PostMapping("/burn")
    @Operation(summary = "Burn YSRUB, withdraw SRUB at current ratio (1:1 in Sprint 9)",
            description = "Burns the requested YSRUB from the caller and returns the equivalent SRUB from the reserve "
                    + "at the current ratio (user id taken from the JWT). Idempotent by idempotencyKey — a replay "
                    + "returns the original movement. Requires an authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Burn succeeded (or idempotent replay); movement returned"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or insufficient YSRUB balance"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "404", description = "SRUB / YSRUB token row not found")
    })
    public ResponseEntity<YsrubMovementResponse> burn(Authentication auth,
                                                       @Valid @RequestBody YsrubBurnRequest req) {
        UUID userId = parseUserId(auth);
        YsrubReserveMovement movement = ysrubService.burn(userId, req.ysrubAmount(), req.idempotencyKey());
        return ResponseEntity.ok(YsrubMovementResponse.from(movement));
    }

    /**
     * Returns the caller's YSRUB deposit/withdrawal history, newest first.
     *
     * @param auth the authenticated principal; the caller's user id is read from it
     * @param page zero-based page index (defaults to 0; negatives are clamped to 0)
     * @param size page size (defaults to 20; clamped to the range 1..100)
     * @return HTTP 200 with the caller's {@link YsrubMovementResponse} list (may be empty)
     */
    @GetMapping("/me")
    @Operation(summary = "My YSRUB movement history (paged, latest first)",
            description = "Returns the caller's YSRUB deposit/withdrawal movements, newest first (user id taken from "
                    + "the JWT). Page size is clamped to 1..100. Requires an authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Caller's movements (may be empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    })
    public ResponseEntity<List<YsrubMovementResponse>> myMovements(
            Authentication auth,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (clamped to 1..100)") @RequestParam(defaultValue = "20") int size) {
        UUID userId = parseUserId(auth);
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        List<YsrubMovementResponse> rows = reserveRepository
                .findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .getContent().stream()
                .map(YsrubMovementResponse::from)
                .toList();
        return ResponseEntity.ok(rows);
    }

    /**
     * Extracts the caller's user id from the security principal, tolerating
     * either a {@link UUID} principal or its string form.
     *
     * @param auth the authenticated principal
     * @return the caller's user id as a {@link UUID}
     */
    private static UUID parseUserId(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof UUID uuid) return uuid;
        return UUID.fromString(principal.toString());
    }

    // ── DTOs (records — wire-compatible, no Lombok needed) ──

    /**
     * Request body for {@code POST /ysrub/mint}.
     *
     * @param srubAmount     SRUB to deposit, raw integer on the ×10⁴ platform scale (must be &gt; 0)
     * @param idempotencyKey client-supplied dedup key making the mint retry-safe (required, non-blank)
     */
    public record YsrubOpRequest(
            @Positive(message = "srubAmount must be > 0") long srubAmount,
            @NotBlank(message = "idempotencyKey required") String idempotencyKey) {}

    /**
     * Request body for {@code POST /ysrub/burn}.
     *
     * @param ysrubAmount    YSRUB to burn, raw integer on the ×10⁴ platform scale (must be &gt; 0)
     * @param idempotencyKey client-supplied dedup key making the burn retry-safe (required, non-blank)
     */
    public record YsrubBurnRequest(
            @Positive(message = "ysrubAmount must be > 0") long ysrubAmount,
            @NotBlank(message = "idempotencyKey required") String idempotencyKey) {}

    /**
     * Response view of a single YSRUB reserve movement (one mint or burn).
     *
     * <p>Built from the {@link YsrubReserveMovement} entity via
     * {@link #from(YsrubReserveMovement)}.
     *
     * @param id          unique movement id
     * @param userId      user the movement belongs to
     * @param direction   movement direction, {@code "DEPOSIT"} (mint) or {@code "WITHDRAWAL"} (burn) — enum name as string
     * @param srubAmount  SRUB side of the movement, raw ×10⁴
     * @param ysrubAmount YSRUB side of the movement, raw ×10⁴
     * @param ratioMicro  conversion ratio applied, in micros (1.0 = 1_000_000); NOT a ×10⁴ amount
     * @param createdAt   timestamp the movement was recorded
     */
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
