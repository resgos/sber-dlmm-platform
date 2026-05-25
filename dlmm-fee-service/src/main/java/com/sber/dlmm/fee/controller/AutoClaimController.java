package com.sber.dlmm.fee.controller;

import com.sber.dlmm.fee.dto.AutoClaimLogDto;
import com.sber.dlmm.fee.dto.AutoClaimPolicyDto;
import com.sber.dlmm.fee.repository.AutoClaimLogRepository;
import com.sber.dlmm.fee.service.AutoClaimPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Sprint 12 G-16 — exposes the auto-claim policy + history to the
 * authenticated user. Mounted under {@code /api/v1/fees/} so the
 * existing gateway route ({@code Path=/api/v1/fees/**}) proxies us
 * without any new route rule. The task spec calls these
 * {@code /api/v1/users/me/auto-claim-policy} in user-prose; the
 * actual gateway-compatible path is {@code /api/v1/fees/me/auto-claim-policy}.
 */
@RestController
@RequestMapping("/api/v1/fees/me/auto-claim-policy")
@RequiredArgsConstructor
public class AutoClaimController {

    private final AutoClaimPolicyService policyService;
    private final AutoClaimLogRepository logRepository;

    @GetMapping
    public ResponseEntity<AutoClaimPolicyDto> getPolicy(Authentication authentication) {
        UUID userId = currentUser(authentication);
        return ResponseEntity.ok(policyService.getOrDefault(userId));
    }

    @PutMapping
    public ResponseEntity<AutoClaimPolicyDto> upsertPolicy(@Valid @RequestBody AutoClaimPolicyDto dto,
                                                            Authentication authentication) {
        UUID userId = currentUser(authentication);
        return ResponseEntity.ok(policyService.upsert(userId, dto));
    }

    @DeleteMapping
    public ResponseEntity<AutoClaimPolicyDto> resetPolicy(Authentication authentication) {
        UUID userId = currentUser(authentication);
        return ResponseEntity.ok(policyService.reset(userId));
    }

    @GetMapping("/history")
    public ResponseEntity<List<AutoClaimLogDto>> getHistory(
            @RequestParam(defaultValue = "20") int limit,
            Authentication authentication) {
        UUID userId = currentUser(authentication);
        int safeLimit = Math.min(Math.max(1, limit), 100);
        List<AutoClaimLogDto> entries = logRepository
                .findByUserIdOrderByFiredAtDesc(userId, PageRequest.of(0, safeLimit))
                .getContent()
                .stream()
                .map(AutoClaimLogDto::from)
                .toList();
        return ResponseEntity.ok(entries);
    }

    /**
     * Matches the JwtAuthenticationFilter contract — principal is the
     * UUID object since Sprint 9; {@code .getName()} stringifies it
     * back. Same pattern used by {@link FeeController}.
     */
    private static UUID currentUser(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof UUID uuid) return uuid;
        return UUID.fromString(authentication.getName());
    }
}
