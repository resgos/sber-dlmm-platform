package com.sber.dlmm.fee.service;

import com.sber.dlmm.fee.dto.AutoClaimPolicyDto;
import com.sber.dlmm.fee.entity.AutoClaimPolicy;
import com.sber.dlmm.fee.repository.AutoClaimPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sprint 12 G-16 — CRUD service over {@link AutoClaimPolicy}.
 *
 * <p>Read-or-default contract: {@link #getOrDefault} never throws
 * NotFound; absent rows mean "user has never touched the settings"
 * which is the (disabled) default.
 *
 * <p>The {@code skipPoolIds} list <-> CSV translation is owned here so
 * the controller stays trivial and the scheduler reads the parsed
 * shape directly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoClaimPolicyService {

    private final AutoClaimPolicyRepository repository;

    @Transactional(readOnly = true)
    public AutoClaimPolicyDto getOrDefault(UUID userId) {
        return repository.findById(userId)
                .map(AutoClaimPolicyService::toDto)
                .orElseGet(() -> defaultDto());
    }

    /**
     * Upsert. Returns the persisted shape (echo) so the frontend
     * doesn't need a follow-up GET.
     */
    @Transactional
    public AutoClaimPolicyDto upsert(UUID userId, AutoClaimPolicyDto dto) {
        AutoClaimPolicy entity = repository.findById(userId).orElseGet(() -> {
            AutoClaimPolicy fresh = new AutoClaimPolicy();
            fresh.setUserId(userId);
            return fresh;
        });
        entity.setEnabled(Boolean.TRUE.equals(dto.enabled()));
        entity.setThresholdAmount(dto.thresholdAmount() != null ? dto.thresholdAmount() : BigDecimal.ZERO);
        entity.setDailyCap(Math.max(0, dto.dailyCap()));
        entity.setSkipPoolIds(joinSkipPoolIds(dto.skipPoolIds()));
        repository.save(entity);
        log.info("Auto-claim policy upserted for user={}: enabled={}, threshold={}, cap={}",
                userId, entity.isEnabled(), entity.getThresholdAmount(), entity.getDailyCap());
        return toDto(entity);
    }

    @Transactional
    public AutoClaimPolicyDto reset(UUID userId) {
        repository.deleteById(userId);
        log.info("Auto-claim policy reset for user={}", userId);
        return defaultDto();
    }

    static AutoClaimPolicyDto defaultDto() {
        return new AutoClaimPolicyDto(false, BigDecimal.valueOf(1_000), 20, Collections.emptyList());
    }

    static AutoClaimPolicyDto toDto(AutoClaimPolicy entity) {
        return new AutoClaimPolicyDto(
                entity.isEnabled(),
                entity.getThresholdAmount(),
                entity.getDailyCap(),
                splitSkipPoolIds(entity.getSkipPoolIds())
        );
    }

    static List<String> splitSkipPoolIds(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    static String joinSkipPoolIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        return ids.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(","));
    }
}
