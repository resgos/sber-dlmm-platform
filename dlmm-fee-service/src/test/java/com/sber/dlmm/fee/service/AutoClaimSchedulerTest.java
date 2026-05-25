package com.sber.dlmm.fee.service;

import com.sber.dlmm.fee.dto.ClaimFeesRequest;
import com.sber.dlmm.fee.dto.ClaimFeesResponse;
import com.sber.dlmm.fee.entity.AutoClaimLog;
import com.sber.dlmm.fee.entity.AutoClaimPolicy;
import com.sber.dlmm.fee.entity.FeeAccrual;
import com.sber.dlmm.fee.repository.AutoClaimLogRepository;
import com.sber.dlmm.fee.repository.AutoClaimPolicyRepository;
import com.sber.dlmm.fee.repository.FeeAccrualRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 12 G-16 — pins the AutoClaimScheduler contract:
 *   - enabled policy with eligible accruals → claim fires + SUCCESS log
 *   - disabled policy → no claim, no log
 *   - per-position 1h cooldown honoured via auto_claim_log
 *   - dailyCap honoured (count successes in 24h ≥ cap → no fire)
 *   - threshold gate (sum < threshold → no fire)
 *   - skipPoolIds honoured
 *   - claim failure logs FAILURE without throwing
 */
class AutoClaimSchedulerTest {

    private AutoClaimPolicyRepository policyRepo;
    private AutoClaimLogRepository logRepo;
    private FeeAccrualRepository feeAccrualRepo;
    private FeeService feeService;
    private AutoClaimScheduler scheduler;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID POSITION_ID = UUID.randomUUID();
    private static final UUID POOL_ID = UUID.randomUUID();
    private static final UUID TOKEN_X = UUID.randomUUID();
    private static final UUID TOKEN_Y = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        policyRepo = mock(AutoClaimPolicyRepository.class);
        logRepo = mock(AutoClaimLogRepository.class);
        feeAccrualRepo = mock(FeeAccrualRepository.class);
        feeService = mock(FeeService.class);
        scheduler = new AutoClaimScheduler(policyRepo, logRepo, feeAccrualRepo, feeService);
    }

    @Test
    void enabledPolicy_withEligibleAccruals_firesClaim_and_logsSuccess() {
        AutoClaimPolicy policy = enabledPolicy(USER_ID, BigDecimal.valueOf(500), 20, "");
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(feeAccrualRepo.findByUserIdAndClaimedFalse(USER_ID)).thenReturn(List.of(
                accrual(POSITION_ID, POOL_ID, TOKEN_X, 800)
        ));
        when(logRepo.findLatestSuccessForPosition(POSITION_ID)).thenReturn(Optional.empty());
        when(logRepo.countSuccessByUserSince(eq(USER_ID), any(LocalDateTime.class))).thenReturn(0L);
        when(feeService.claimFees(any(ClaimFeesRequest.class), eq(USER_ID)))
                .thenReturn(new ClaimFeesResponse(POSITION_ID, 800, 0, TOKEN_X, null));

        scheduler.tick();

        verify(feeService, times(1)).claimFees(any(ClaimFeesRequest.class), eq(USER_ID));
        ArgumentCaptor<AutoClaimLog> logCaptor = ArgumentCaptor.forClass(AutoClaimLog.class);
        verify(logRepo).save(logCaptor.capture());
        AutoClaimLog saved = logCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AutoClaimLog.Status.SUCCESS);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getPositionId()).isEqualTo(POSITION_ID);
        assertThat(saved.getAmountX()).isEqualTo(800);
        assertThat(saved.getAmountY()).isEqualTo(0);
    }

    @Test
    void disabledPolicy_isNotReturnedByFindByEnabledTrue_andNeverFires() {
        // findByEnabledTrue is the gate; disabled policies don't even make
        // it to the scheduler. Belt-and-braces: if they did, no claim fires
        // because the policy list is empty in this test.
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of());

        scheduler.tick();

        verify(feeService, never()).claimFees(any(), any());
        verify(logRepo, never()).save(any());
    }

    @Test
    void cooldown_honoured_recentSuccess_skipsFire() {
        AutoClaimPolicy policy = enabledPolicy(USER_ID, BigDecimal.valueOf(500), 20, "");
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(feeAccrualRepo.findByUserIdAndClaimedFalse(USER_ID)).thenReturn(List.of(
                accrual(POSITION_ID, POOL_ID, TOKEN_X, 800)
        ));
        // Most recent SUCCESS was 30 minutes ago → still on cooldown.
        AutoClaimLog recent = AutoClaimLog.builder()
                .userId(USER_ID)
                .positionId(POSITION_ID)
                .poolId(POOL_ID)
                .amountX(100)
                .amountY(0)
                .status(AutoClaimLog.Status.SUCCESS)
                .firedAt(LocalDateTime.now().minusMinutes(30))
                .build();
        when(logRepo.findLatestSuccessForPosition(POSITION_ID)).thenReturn(Optional.of(recent));
        when(logRepo.countSuccessByUserSince(eq(USER_ID), any(LocalDateTime.class))).thenReturn(0L);

        scheduler.tick();

        verify(feeService, never()).claimFees(any(), any());
        verify(logRepo, never()).save(any());
    }

    @Test
    void cooldown_expired_oldSuccess_allowsFire() {
        AutoClaimPolicy policy = enabledPolicy(USER_ID, BigDecimal.valueOf(500), 20, "");
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(feeAccrualRepo.findByUserIdAndClaimedFalse(USER_ID)).thenReturn(List.of(
                accrual(POSITION_ID, POOL_ID, TOKEN_X, 800)
        ));
        AutoClaimLog old = AutoClaimLog.builder()
                .userId(USER_ID).positionId(POSITION_ID).poolId(POOL_ID)
                .amountX(100).amountY(0)
                .status(AutoClaimLog.Status.SUCCESS)
                .firedAt(LocalDateTime.now().minusHours(2))
                .build();
        when(logRepo.findLatestSuccessForPosition(POSITION_ID)).thenReturn(Optional.of(old));
        when(logRepo.countSuccessByUserSince(eq(USER_ID), any(LocalDateTime.class))).thenReturn(0L);
        when(feeService.claimFees(any(), eq(USER_ID)))
                .thenReturn(new ClaimFeesResponse(POSITION_ID, 800, 0, TOKEN_X, null));

        scheduler.tick();

        verify(feeService, times(1)).claimFees(any(), eq(USER_ID));
    }

    @Test
    void dailyCap_reached_skipsAllFiresForUser() {
        AutoClaimPolicy policy = enabledPolicy(USER_ID, BigDecimal.valueOf(500), 5, "");
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(feeAccrualRepo.findByUserIdAndClaimedFalse(USER_ID)).thenReturn(List.of(
                accrual(POSITION_ID, POOL_ID, TOKEN_X, 800)
        ));
        // 24h count >= cap → policy is capped, scheduler returns early.
        when(logRepo.countSuccessByUserSince(eq(USER_ID), any(LocalDateTime.class))).thenReturn(5L);

        scheduler.tick();

        verify(feeService, never()).claimFees(any(), any());
        verify(logRepo, never()).save(any());
    }

    @Test
    void dailyCap_zero_means_unlimited_and_doesNotShortCircuit() {
        AutoClaimPolicy policy = enabledPolicy(USER_ID, BigDecimal.valueOf(500), 0, "");
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(feeAccrualRepo.findByUserIdAndClaimedFalse(USER_ID)).thenReturn(List.of(
                accrual(POSITION_ID, POOL_ID, TOKEN_X, 800)
        ));
        when(logRepo.findLatestSuccessForPosition(POSITION_ID)).thenReturn(Optional.empty());
        // countSuccessByUserSince might return any number — cap=0 means
        // it's never checked. We don't stub it strictly; default returns 0.
        when(feeService.claimFees(any(), eq(USER_ID)))
                .thenReturn(new ClaimFeesResponse(POSITION_ID, 800, 0, TOKEN_X, null));

        scheduler.tick();

        verify(feeService, times(1)).claimFees(any(), eq(USER_ID));
    }

    @Test
    void belowThreshold_doesNotFire() {
        AutoClaimPolicy policy = enabledPolicy(USER_ID, BigDecimal.valueOf(10_000), 20, "");
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(feeAccrualRepo.findByUserIdAndClaimedFalse(USER_ID)).thenReturn(List.of(
                accrual(POSITION_ID, POOL_ID, TOKEN_X, 500),
                accrual(POSITION_ID, POOL_ID, TOKEN_Y, 400)
                // sum = 900 < threshold 10_000
        ));
        when(logRepo.findLatestSuccessForPosition(POSITION_ID)).thenReturn(Optional.empty());
        when(logRepo.countSuccessByUserSince(eq(USER_ID), any(LocalDateTime.class))).thenReturn(0L);

        scheduler.tick();

        verify(feeService, never()).claimFees(any(), any());
    }

    @Test
    void skipPoolIds_honoured() {
        AutoClaimPolicy policy = enabledPolicy(USER_ID, BigDecimal.valueOf(500), 20, POOL_ID.toString());
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(feeAccrualRepo.findByUserIdAndClaimedFalse(USER_ID)).thenReturn(List.of(
                accrual(POSITION_ID, POOL_ID, TOKEN_X, 800)
        ));
        when(logRepo.countSuccessByUserSince(eq(USER_ID), any(LocalDateTime.class))).thenReturn(0L);

        scheduler.tick();

        verify(feeService, never()).claimFees(any(), any());
    }

    @Test
    void claimFailure_logsFailure_andDoesNotPropagate() {
        AutoClaimPolicy policy = enabledPolicy(USER_ID, BigDecimal.valueOf(500), 20, "");
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of(policy));
        when(feeAccrualRepo.findByUserIdAndClaimedFalse(USER_ID)).thenReturn(List.of(
                accrual(POSITION_ID, POOL_ID, TOKEN_X, 800)
        ));
        when(logRepo.findLatestSuccessForPosition(POSITION_ID)).thenReturn(Optional.empty());
        when(logRepo.countSuccessByUserSince(eq(USER_ID), any(LocalDateTime.class))).thenReturn(0L);
        when(feeService.claimFees(any(), eq(USER_ID)))
                .thenThrow(new RuntimeException("token-service unavailable"));

        scheduler.tick(); // must not throw

        ArgumentCaptor<AutoClaimLog> captor = ArgumentCaptor.forClass(AutoClaimLog.class);
        verify(logRepo).save(captor.capture());
        AutoClaimLog saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AutoClaimLog.Status.FAILURE);
        assertThat(saved.getErrorMessage()).contains("token-service unavailable");
    }

    @Test
    void emptyEnabledPolicies_doesNothing_quietly() {
        when(policyRepo.findByEnabledTrue()).thenReturn(List.of());
        scheduler.tick();
        verify(feeAccrualRepo, never()).findByUserIdAndClaimedFalse(any());
        verify(feeService, never()).claimFees(any(), any());
    }

    // ----- fixtures ---------------------------------------------------

    private static AutoClaimPolicy enabledPolicy(UUID userId, BigDecimal threshold, int dailyCap, String skipCsv) {
        return AutoClaimPolicy.builder()
                .userId(userId)
                .enabled(true)
                .thresholdAmount(threshold)
                .dailyCap(dailyCap)
                .skipPoolIds(skipCsv)
                .build();
    }

    private static FeeAccrual accrual(UUID positionId, UUID poolId, UUID tokenId, long amount) {
        return FeeAccrual.builder()
                .id(UUID.randomUUID())
                .positionId(positionId)
                .poolId(poolId)
                .userId(USER_ID)
                .tokenId(tokenId)
                .amount(amount)
                .claimed(false)
                .accruedAt(LocalDateTime.now().minusMinutes(5))
                .build();
    }
}
