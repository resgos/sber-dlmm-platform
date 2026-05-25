package com.sber.dlmm.fee.service;

import com.sber.dlmm.fee.dto.AutoClaimPolicyDto;
import com.sber.dlmm.fee.entity.AutoClaimPolicy;
import com.sber.dlmm.fee.repository.AutoClaimPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoClaimPolicyServiceTest {

    private AutoClaimPolicyRepository repo;
    private AutoClaimPolicyService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(AutoClaimPolicyRepository.class);
        service = new AutoClaimPolicyService(repo);
    }

    @Test
    void getOrDefault_missingRow_returnsDefault() {
        when(repo.findById(userId)).thenReturn(Optional.empty());
        AutoClaimPolicyDto dto = service.getOrDefault(userId);
        assertThat(dto.enabled()).isFalse();
        assertThat(dto.thresholdAmount()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(dto.dailyCap()).isEqualTo(20);
        assertThat(dto.skipPoolIds()).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    void getOrDefault_existingRow_mapsCsvSkipPoolIdsToList() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        AutoClaimPolicy stored = AutoClaimPolicy.builder()
                .userId(userId)
                .enabled(true)
                .thresholdAmount(BigDecimal.valueOf(750))
                .dailyCap(5)
                .skipPoolIds(p1 + "," + p2)
                .build();
        when(repo.findById(userId)).thenReturn(Optional.of(stored));

        AutoClaimPolicyDto dto = service.getOrDefault(userId);

        assertThat(dto.enabled()).isTrue();
        assertThat(dto.thresholdAmount()).isEqualByComparingTo(BigDecimal.valueOf(750));
        assertThat(dto.dailyCap()).isEqualTo(5);
        assertThat(dto.skipPoolIds()).containsExactly(p1.toString(), p2.toString());
    }

    @Test
    void upsert_creates_newRow_when_missing() {
        when(repo.findById(userId)).thenReturn(Optional.empty());
        AutoClaimPolicyDto input = new AutoClaimPolicyDto(true, BigDecimal.valueOf(2500), 10, List.of("pool-1", "pool-2"));

        service.upsert(userId, input);

        ArgumentCaptor<AutoClaimPolicy> captor = ArgumentCaptor.forClass(AutoClaimPolicy.class);
        verify(repo, times(1)).save(captor.capture());
        AutoClaimPolicy saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getThresholdAmount()).isEqualByComparingTo(BigDecimal.valueOf(2500));
        assertThat(saved.getDailyCap()).isEqualTo(10);
        assertThat(saved.getSkipPoolIds()).isEqualTo("pool-1,pool-2");
    }

    @Test
    void upsert_mutates_existingRow_inPlace() {
        AutoClaimPolicy existing = AutoClaimPolicy.builder()
                .userId(userId)
                .enabled(false)
                .thresholdAmount(BigDecimal.valueOf(100))
                .dailyCap(20)
                .skipPoolIds("")
                .build();
        when(repo.findById(userId)).thenReturn(Optional.of(existing));
        AutoClaimPolicyDto input = new AutoClaimPolicyDto(true, BigDecimal.valueOf(3000), 7, List.of());

        service.upsert(userId, input);

        verify(repo).save(existing);
        assertThat(existing.isEnabled()).isTrue();
        assertThat(existing.getThresholdAmount()).isEqualByComparingTo(BigDecimal.valueOf(3000));
        assertThat(existing.getDailyCap()).isEqualTo(7);
    }

    @Test
    void reset_deletesById_andReturnsDefault() {
        AutoClaimPolicyDto result = service.reset(userId);
        verify(repo).deleteById(userId);
        assertThat(result.enabled()).isFalse();
        assertThat(result.thresholdAmount()).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    void splitSkipPoolIds_handlesNullEmptyAndWhitespace() {
        assertThat(AutoClaimPolicyService.splitSkipPoolIds(null)).isEmpty();
        assertThat(AutoClaimPolicyService.splitSkipPoolIds("")).isEmpty();
        assertThat(AutoClaimPolicyService.splitSkipPoolIds("   ")).isEmpty();
        assertThat(AutoClaimPolicyService.splitSkipPoolIds("a,b,  ,c"))
                .containsExactly("a", "b", "c");
    }

    @Test
    void joinSkipPoolIds_handlesNullEmpty() {
        assertThat(AutoClaimPolicyService.joinSkipPoolIds(null)).isEmpty();
        assertThat(AutoClaimPolicyService.joinSkipPoolIds(List.of())).isEmpty();
        assertThat(AutoClaimPolicyService.joinSkipPoolIds(List.of("a", "b", "c"))).isEqualTo("a,b,c");
    }
}
