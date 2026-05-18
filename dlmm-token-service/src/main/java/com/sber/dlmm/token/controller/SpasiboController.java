package com.sber.dlmm.token.controller;

import com.sber.dlmm.token.dto.SpasiboConvertRequest;
import com.sber.dlmm.token.dto.SpasiboMintWebhookRequest;
import com.sber.dlmm.token.dto.SpasiboOperationResponse;
import com.sber.dlmm.token.entity.SpasiboOperation;
import com.sber.dlmm.token.service.SpasiboService;
import io.swagger.v3.oas.annotations.Operation;
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
public class SpasiboController {

    private final SpasiboService spasiboService;

    /**
     * SberSpasibo BU webhook entry-point. Idempotent by {@code reference}
     * (Spasibo external event id).
     */
    @PostMapping("/webhook")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "SberSpasibo BU webhook — mint SSPAS to user (ADMIN; prototype gate)")
    public ResponseEntity<SpasiboOperationResponse> mintWebhook(@Valid @RequestBody SpasiboMintWebhookRequest req) {
        SpasiboOperation op = spasiboService.handleMintWebhook(req.userId(), req.points(), req.reference());
        return ResponseEntity.status(HttpStatus.CREATED).body(SpasiboOperationResponse.from(op));
    }

    /**
     * User-initiated conversion. Direct burn-mint pair (not a pool swap),
     * fixed-rate, zero-fee. Sprint 6+ can introduce tiered rates / promo
     * boosts here.
     */
    @PostMapping("/convert")
    @Operation(summary = "Convert SSPAS → SRUB at fixed rate (any authenticated user, own balance only)")
    public ResponseEntity<SpasiboOperationResponse> convert(Authentication auth,
                                                              @Valid @RequestBody SpasiboConvertRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        SpasiboOperation op = spasiboService.convertToRub(userId, req.points(), req.reference());
        return ResponseEntity.status(HttpStatus.CREATED).body(SpasiboOperationResponse.from(op));
    }
}
