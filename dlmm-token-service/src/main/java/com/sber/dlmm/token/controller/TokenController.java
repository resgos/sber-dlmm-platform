package com.sber.dlmm.token.controller;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.token.dto.BalanceResponse;
import com.sber.dlmm.token.dto.BurnRequest;
import com.sber.dlmm.token.dto.CreateTokenRequest;
import com.sber.dlmm.token.dto.MintRequest;
import com.sber.dlmm.token.dto.TokenResponse;
import com.sber.dlmm.token.dto.TransferRequest;
import com.sber.dlmm.token.service.TokenService;
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

@RestController
@RequestMapping("/api/v1")
public class TokenController {

    private final TokenService tokenService;

    public TokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping("/tokens")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<TokenResponse> createToken(@Valid @RequestBody CreateTokenRequest request,
                                                     Authentication authentication) {
        UUID adminUserId = (UUID) authentication.getPrincipal();
        TokenResponse response = tokenService.createToken(request, adminUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/tokens")
    public ResponseEntity<PageResponse<TokenResponse>> getAllTokens(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<TokenResponse> response = tokenService.getAllTokens(page, size);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/tokens/{id}")
    public ResponseEntity<TokenResponse> getToken(@PathVariable UUID id) {
        TokenResponse response = tokenService.getToken(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/tokens/symbol/{symbol}")
    public ResponseEntity<TokenResponse> getTokenBySymbol(@PathVariable String symbol) {
        TokenResponse response = tokenService.getTokenBySymbol(symbol);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/tokens/mint")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<BalanceResponse> mint(@Valid @RequestBody MintRequest request,
                                                Authentication authentication) {
        UUID adminUserId = (UUID) authentication.getPrincipal();
        BalanceResponse response = tokenService.mint(request, adminUserId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/tokens/burn")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<BalanceResponse> burn(@Valid @RequestBody BurnRequest request,
                                                Authentication authentication) {
        UUID adminUserId = (UUID) authentication.getPrincipal();
        BalanceResponse response = tokenService.burn(request, adminUserId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/tokens/{id}/pause")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<TokenResponse> pauseToken(@PathVariable UUID id) {
        TokenResponse response = tokenService.pauseToken(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/tokens/{id}/unpause")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<TokenResponse> unpauseToken(@PathVariable UUID id) {
        TokenResponse response = tokenService.unpauseToken(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/tokens/transfer")
    public ResponseEntity<Void> transfer(@Valid @RequestBody TransferRequest request) {
        tokenService.transfer(request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/balances/me")
    public ResponseEntity<List<BalanceResponse>> getMyBalances(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        List<BalanceResponse> response = tokenService.getUserBalances(userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/balances/me/{tokenId}")
    public ResponseEntity<BalanceResponse> getMyTokenBalance(@PathVariable UUID tokenId,
                                                             Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        BalanceResponse response = tokenService.getUserTokenBalance(userId, tokenId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/balances/user/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<BalanceResponse>> getUserBalances(@PathVariable UUID userId) {
        List<BalanceResponse> response = tokenService.getUserBalances(userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Internal endpoints consumed by pool-engine during swap / add-liquidity /
     * remove-liquidity. Any authenticated user can invoke them — pool-engine
     * forwards the caller's Bearer via BearerTokenForwardingFilter, so these
     * effectively run "on behalf of" the original user. The deduct path
     * enforces ownership via userId in the body matching balance owner.
     */
    @PostMapping("/tokens/internal/deduct")
    public ResponseEntity<Void> deductInternal(@Valid @RequestBody InternalBalanceRequest req) {
        tokenService.deductInternal(req.userId(), req.tokenId(), req.amount());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tokens/internal/credit")
    public ResponseEntity<Void> creditInternal(@Valid @RequestBody InternalBalanceRequest req) {
        tokenService.creditInternal(req.userId(), req.tokenId(), req.amount());
        return ResponseEntity.noContent().build();
    }

    public record InternalBalanceRequest(
            @jakarta.validation.constraints.NotNull UUID userId,
            @jakarta.validation.constraints.NotNull UUID tokenId,
            @jakarta.validation.constraints.Positive long amount
    ) {}
}
