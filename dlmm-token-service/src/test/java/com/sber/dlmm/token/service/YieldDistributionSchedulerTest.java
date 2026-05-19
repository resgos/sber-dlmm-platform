package com.sber.dlmm.token.service;

import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.token.entity.Token;
import com.sber.dlmm.token.entity.YsrubYieldAccrual;
import com.sber.dlmm.token.repository.TokenRepository;
import com.sber.dlmm.token.repository.UserBalanceRepository;
import com.sber.dlmm.token.repository.YsrubYieldAccrualRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sprint 9 #7.2 — pins the yield calculation + writer contract.
 *
 * <p>Two nested suites: {@link CalculateYieldTests} for the pure
 * arithmetic (no Mockito), {@link AccrueForHolderTests} for the
 * transactional writer with mocked repositories.
 */
@ExtendWith(MockitoExtension.class)
class YieldDistributionSchedulerTest {

    @Nested
    @DisplayName("calculateYield (pure)")
    class CalculateYieldTests {

        @Test
        @DisplayName("1M YSRUB × 15% pa + 30bps spread → ~419 / day")
        void typicalDailyYield() {
            // 1_000_000 × (1500 + 30) / 10_000 / 365
            //   = 1_000_000 × 1530 / 10_000 / 365
            //   = 153_000 / 365 = 419.17 → floor 419
            long yield = YieldDistributionScheduler.calculateYield(1_000_000L, 1500, 30);
            assertEquals(419L, yield);
        }

        @Test
        @DisplayName("100k YSRUB × 15% pa → ~41 / day (sub-1k principal still earns)")
        void smallPrincipal() {
            // 100_000 × 1530 / 10_000 / 365 = 15_300 / 365 = 41.92 → 41
            assertEquals(41L, YieldDistributionScheduler.calculateYield(100_000L, 1500, 30));
        }

        @Test
        @DisplayName("very small principal → 0 (floor rounds down — no over-distribution)")
        void principalTooSmallReturnsZero() {
            // 1000 × 1530 / 10_000 / 365 = 153 / 365 = 0.42 → 0
            assertEquals(0L, YieldDistributionScheduler.calculateYield(1000L, 1500, 30));
            // 10 × 1530 / 10_000 / 365 = 15.3 / 365 = 0.04 → 0
            assertEquals(0L, YieldDistributionScheduler.calculateYield(10L, 1500, 30));
        }

        @Test
        @DisplayName("zero or negative principal → 0 (defensive)")
        void zeroOrNegativePrincipal() {
            assertEquals(0L, YieldDistributionScheduler.calculateYield(0L, 1500, 30));
            assertEquals(0L, YieldDistributionScheduler.calculateYield(-100L, 1500, 30));
        }

        @Test
        @DisplayName("zero rate → 0 yield (rate-off switch)")
        void zeroRateGivesZeroYield() {
            assertEquals(0L, YieldDistributionScheduler.calculateYield(1_000_000L, 0, 0));
        }

        @Test
        @DisplayName("yield scales linearly with principal")
        void yieldScalesLinearly() {
            long base = YieldDistributionScheduler.calculateYield(1_000_000L, 1500, 30);
            long double_ = YieldDistributionScheduler.calculateYield(2_000_000L, 1500, 30);
            // 2× principal → 2× yield (within floor rounding)
            assertThat(double_).isBetween(base * 2 - 1, base * 2 + 1);
        }

        @Test
        @DisplayName("large principal does not overflow")
        void largePrincipalNoOverflow() {
            // 1 trillion YSRUB × 2000 bps total = 2 × 10^15 (within long range 9.2 × 10^18)
            long yield = YieldDistributionScheduler.calculateYield(1_000_000_000_000L, 1500, 30);
            assertThat(yield).isGreaterThan(0);
            assertThat(yield).isLessThan(1_000_000_000_000L);   // sanity
        }
    }

    @Nested
    @DisplayName("YieldAccrualWriter.accrueForHolder")
    class AccrueForHolderTests {

        private static final UUID YSRUB_ID = UUID.fromString("b0000000-0000-0000-0000-000000000007");

        @Mock private UserBalanceRepository userBalanceRepository;
        @Mock private YsrubYieldAccrualRepository accrualRepository;
        @Mock private TokenRepository tokenRepository;
        @Mock private OutboxService outbox;

        private YieldDistributionScheduler.YieldAccrualWriter writer;
        private Token ysrubToken;

        @BeforeEach
        void setUp() {
            writer = new YieldDistributionScheduler.YieldAccrualWriter(
                    userBalanceRepository, accrualRepository, tokenRepository, outbox);
            ysrubToken = new Token();
            ysrubToken.setId(YSRUB_ID);
            ysrubToken.setSymbol("YSRUB");
            ysrubToken.setTotalSupply(100_000_000L);
        }

        @Test
        @DisplayName("happy path: yield credited, accrual row saved, totalSupply bumped")
        void happyPath() {
            UUID userId = UUID.randomUUID();
            LocalDate day = LocalDate.of(2026, 7, 16);
            when(accrualRepository.findByUserIdAndAccrualDay(userId, day))
                    .thenReturn(Optional.empty());
            when(userBalanceRepository.creditAvailable(userId, YSRUB_ID, 419L))
                    .thenReturn(1);
            when(tokenRepository.findById(YSRUB_ID)).thenReturn(Optional.of(ysrubToken));
            when(accrualRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            long credited = writer.accrueForHolder(userId, 1_000_000L, day, 1500, 30);

            assertEquals(419L, credited);
            assertEquals(100_000_419L, ysrubToken.getTotalSupply());   // 100M + 419

            ArgumentCaptor<YsrubYieldAccrual> cap = ArgumentCaptor.forClass(YsrubYieldAccrual.class);
            verify(accrualRepository).save(cap.capture());
            YsrubYieldAccrual saved = cap.getValue();
            assertThat(saved.getUserId()).isEqualTo(userId);
            assertThat(saved.getPrincipalAtAccrual()).isEqualTo(1_000_000L);
            assertThat(saved.getYieldAmount()).isEqualTo(419L);
            assertThat(saved.getOvernightRateBps()).isEqualTo(1500);
            assertThat(saved.getSpreadBps()).isEqualTo(30);
            assertThat(saved.getAccrualDay()).isEqualTo(day);
        }

        @Test
        @DisplayName("idempotency: existing accrual short-circuits — no double-credit")
        void existingAccrualShortCircuits() {
            UUID userId = UUID.randomUUID();
            LocalDate day = LocalDate.of(2026, 7, 16);
            when(accrualRepository.findByUserIdAndAccrualDay(userId, day))
                    .thenReturn(Optional.of(new YsrubYieldAccrual()));

            long credited = writer.accrueForHolder(userId, 1_000_000L, day, 1500, 30);

            assertEquals(0L, credited);
            // Critical: no credit, no token bump, no save when already accrued
            verify(userBalanceRepository, never()).creditAvailable(any(), any(), org.mockito.ArgumentMatchers.anyLong());
            verify(accrualRepository, never()).save(any());
            verify(tokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("zero yield (tiny principal): no credit, no accrual row")
        void zeroYieldSkips() {
            UUID userId = UUID.randomUUID();
            when(accrualRepository.findByUserIdAndAccrualDay(any(), any()))
                    .thenReturn(Optional.empty());

            long credited = writer.accrueForHolder(userId, 1000L, LocalDate.now(), 1500, 30);

            assertEquals(0L, credited);
            verify(userBalanceRepository, never()).creditAvailable(any(), any(), org.mockito.ArgumentMatchers.anyLong());
            verify(accrualRepository, never()).save(any());
        }

        @Test
        @DisplayName("credit fails (no balance row) → IllegalStateException")
        void creditFailureBubblesUp() {
            UUID userId = UUID.randomUUID();
            when(accrualRepository.findByUserIdAndAccrualDay(any(), any()))
                    .thenReturn(Optional.empty());
            when(userBalanceRepository.creditAvailable(any(), eq(YSRUB_ID), org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn(0);

            assertThatThrownBy(() ->
                    writer.accrueForHolder(userId, 1_000_000L, LocalDate.now(), 1500, 30))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to credit YSRUB yield");
        }
    }
}
