package com.sber.dlmm.fee.controller;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.fee.dto.ClaimFeesRequest;
import com.sber.dlmm.fee.dto.ClaimFeesResponse;
import com.sber.dlmm.fee.dto.FeeAccrualDto;
import com.sber.dlmm.fee.dto.FeesSummaryResponse;
import com.sber.dlmm.fee.service.FeeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fees")
@RequiredArgsConstructor
public class FeeController {

    private final FeeService feeService;

    @GetMapping("/me/summary")
    public ResponseEntity<FeesSummaryResponse> getUserFeesSummary(Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        FeesSummaryResponse response = feeService.getUserFeesSummary(userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/claim")
    public ResponseEntity<ClaimFeesResponse> claimFees(@Valid @RequestBody ClaimFeesRequest request,
                                                       Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        ClaimFeesResponse response = feeService.claimFees(request, userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me/history")
    public ResponseEntity<PageResponse<FeeAccrualDto>> getFeeHistory(
            @RequestParam(required = false) UUID poolId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        UUID userId = UUID.fromString(authentication.getName());
        PageResponse<FeeAccrualDto> response = feeService.getFeeHistory(userId, poolId, page, size);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/user/{userId}/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<FeesSummaryResponse> getUserFeesSummaryByAdmin(@PathVariable UUID userId) {
        FeesSummaryResponse response = feeService.getUserFeesSummary(userId);
        return ResponseEntity.ok(response);
    }
}
