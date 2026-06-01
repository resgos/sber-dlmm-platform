package com.sber.dlmm.token.controller;

import com.sber.dlmm.token.dto.SpasiboConvertRequest;
import com.sber.dlmm.token.dto.SpasiboMintWebhookRequest;
import com.sber.dlmm.token.dto.SpasiboOperationResponse;
import com.sber.dlmm.token.entity.SpasiboOperation;
import com.sber.dlmm.token.service.SpasiboService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Sprint 5 #5.3 + #5.4 — SberSpasibo integration endpoints.
 *
 * <ul>
 *   <li>{@code POST /spasibo/webhook} — SberSpasibo BU pushes earned-points
 *       events (#5.3). Currently ADMIN-gated as prototype safeguard;
 *       production needs a dedicated SPASIBO_WEBHOOK role with whitelist
 *       of the Spasibo service-account JWT.</li>
 *   <li>{@code POST /spasibo/convert} — user-initiated SSPAS → SRUB at
 *       fixed rate, zero fee (#5.4). Caller can only convert their own
 *       SSPAS balance (userId taken from JWT, never from body).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/spasibo")
@RequiredArgsConstructor
@Tag(name = "SberSpasibo Loyalty", description = "Sprint 5 #5.3 + #5.4 — SberSpasibo points mint webhook and SSPAS to SRUB conversion")
public class SpasiboController {

    private final SpasiboService spasiboService;

    /**
     * SberSpasibo BU webhook entry-point. Idempotent by {@code reference}
     * (Spasibo external event id). Currently ADMIN-gated as a prototype
     * safeguard.
     *
     * @param req validated webhook body (target user, earned points, Spasibo event reference)
     * @return HTTP 201 with the recorded {@link SpasiboOperationResponse} (or the existing one on replay)
     */
    @PostMapping("/webhook")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "SberSpasibo BU webhook — mint SSPAS to user (ADMIN; prototype gate)",
            description = "Credits SSPAS loyalty points to a user from an earned-points event. Idempotent by reference "
                    + "(a replay returns the existing operation). Currently restricted to ADMIN / SUPER_ADMIN as a "
                    + "prototype safeguard until a dedicated Spasibo service-account role exists.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "SSPAS minted (or idempotent replay); operation returned"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN / SUPER_ADMIN role"),
            @ApiResponse(responseCode = "404", description = "SSPAS token not found")
    })
    public ResponseEntity<SpasiboOperationResponse> mintWebhook(@Valid @RequestBody SpasiboMintWebhookRequest req) {
        SpasiboOperation op = spasiboService.handleMintWebhook(req.userId(), req.points(), req.reference());
        return ResponseEntity.status(HttpStatus.CREATED).body(SpasiboOperationResponse.from(op));
    }

    /**
     * User-initiated conversion. Direct burn-mint pair (not a pool swap),
     * fixed-rate, zero-fee. Sprint 6+ can introduce tiered rates / promo
     * boosts here. The user id comes from the JWT, so a caller can only
     * convert their own SSPAS balance.
     *
     * @param auth the authenticated principal; the caller's user id is read from it
     * @param req  validated convert body (points to convert, idempotency reference)
     * @return HTTP 201 with the recorded {@link SpasiboOperationResponse} (or the existing one on replay)
     */
    @PostMapping("/convert")
    @Operation(summary = "Convert SSPAS → SRUB at fixed rate (any authenticated user, own balance only)",
            description = "Burns the caller's SSPAS and credits the SRUB equivalent at a fixed, zero-fee rate as a "
                    + "direct burn-mint pair (not a pool swap). The user id comes from the JWT, never the body, so a "
                    + "caller can only convert their own balance. Idempotent by reference. Requires an authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Converted (or idempotent replay); operation returned"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or insufficient SSPAS balance"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "404", description = "SSPAS / SRUB token not found")
    })
    public ResponseEntity<SpasiboOperationResponse> convert(Authentication auth,
                                                              @Valid @RequestBody SpasiboConvertRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        SpasiboOperation op = spasiboService.convertToRub(userId, req.points(), req.reference());
        return ResponseEntity.status(HttpStatus.CREATED).body(SpasiboOperationResponse.from(op));
    }
}
