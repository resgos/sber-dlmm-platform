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

    /**
     * Reads the user's auto-claim policy, or the (disabled) platform default if they
     * have never saved one. Never throws NotFound — an absent row is a valid "untouched
     * settings" state, so the frontend can always render a form. Read-only.
     *
     * @param userId the user whose policy to read (primary key of the policy table)
     * @return the stored policy mapped to a DTO, or {@link #defaultDto()} when none exists
     */
    @Transactional(readOnly = true)
    public AutoClaimPolicyDto getOrDefault(UUID userId) {
        return repository.findById(userId)
                .map(AutoClaimPolicyService::toDto)
                .orElseGet(() -> defaultDto());
    }

    /**
     * Upsert. Returns the persisted shape (echo) so the frontend
     * doesn't need a follow-up GET.
     *
     * <p>Loads the existing row (or builds a fresh one keyed by {@code userId}) and
     * overwrites every field from the DTO, coercing inputs to safe values: a null
     * {@code enabled} becomes {@code false}, a null threshold becomes {@code BigDecimal.ZERO},
     * and a negative {@code dailyCap} is floored to {@code 0} (unlimited). The
     * {@code skipPoolIds} list is flattened to CSV for storage.
     *
     * @param userId the policy owner (primary key)
     * @param dto    the desired policy state from the request body
     * @return the stored policy re-read into a DTO (so the client sees the normalised values)
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

    /**
     * Deletes the user's policy row so reads fall back to the default again. Idempotent —
     * deleting a non-existent row is a no-op. Returns the default the caller will now see.
     *
     * @param userId the policy owner whose row to remove
     * @return {@link #defaultDto()}, the state the user reverts to
     */
    @Transactional
    public AutoClaimPolicyDto reset(UUID userId) {
        repository.deleteById(userId);
        log.info("Auto-claim policy reset for user={}", userId);
        return defaultDto();
    }

    /**
     * The platform-default policy returned when a user has none: <b>disabled</b>, a 1,000
     * threshold, a daily cap of 20, and no skipped pools. Disabled-by-default is the safe
     * choice — auto-claiming money only happens after the user explicitly opts in.
     *
     * @return a fresh default {@link AutoClaimPolicyDto}
     */
    static AutoClaimPolicyDto defaultDto() {
        return new AutoClaimPolicyDto(false, BigDecimal.valueOf(1_000), 20, Collections.emptyList());
    }

    /**
     * Maps a stored {@link AutoClaimPolicy} entity to its DTO, parsing the CSV
     * {@code skipPoolIds} column back into a list for the client.
     *
     * @param entity the persisted policy
     * @return the transport DTO
     */
    static AutoClaimPolicyDto toDto(AutoClaimPolicy entity) {
        return new AutoClaimPolicyDto(
                entity.isEnabled(),
                entity.getThresholdAmount(),
                entity.getDailyCap(),
                splitSkipPoolIds(entity.getSkipPoolIds())
        );
    }

    /**
     * Parses the stored CSV {@code skipPoolIds} column into a clean list, trimming each
     * id and dropping blanks. The inverse of {@link #joinSkipPoolIds(List)}. Also reused
     * by the scheduler to read the parsed shape directly.
     *
     * @param csv the comma-separated pool ids from the entity ({@code null}/blank ⇒ empty list)
     * @return the pool ids as a list (never {@code null})
     */
    static List<String> splitSkipPoolIds(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Flattens a list of skip-pool ids into the CSV form stored on the entity, dropping
     * nulls/blanks and trimming each id. The inverse of {@link #splitSkipPoolIds(String)}.
     *
     * @param ids the pool ids to skip ({@code null}/empty ⇒ empty string)
     * @return a comma-joined string suitable for the {@code skip_pool_ids} column
     */
    static String joinSkipPoolIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        return ids.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(","));
    }
}
