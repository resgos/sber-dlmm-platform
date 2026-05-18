package com.sber.dlmm.transaction.service;

import com.sber.dlmm.common.enums.TransactionStatus;
import com.sber.dlmm.common.enums.TransactionType;
import com.sber.dlmm.transaction.entity.AmlAlert;
import com.sber.dlmm.transaction.entity.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 6 #6.9 — locks the AML detection rules. Pure logic, no Spring.
 */
class AmlPatternDetectionServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 3, 12, 0);

    private Transaction tx(LocalDateTime created, long amountIn, long amountOut, TransactionType type) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .userId(USER)
                .txType(type)
                .status(TransactionStatus.CONFIRMED)
                .amountIn(amountIn)
                .amountOut(amountOut)
                .feeAmount(0L)
                .createdAt(created)
                .build();
    }

    @Nested
    @DisplayName("detectRoundAmountRepeats")
    class RoundAmountTests {

        @Test
        @DisplayName("3 identical amounts within 1h → fires")
        void threeIdenticalWithin1h() {
            var txs = List.of(
                    tx(NOW.minusMinutes(50), 100_000, 95_000, TransactionType.SWAP),
                    tx(NOW.minusMinutes(30), 100_000, 95_000, TransactionType.SWAP),
                    tx(NOW.minusMinutes(10), 100_000, 95_000, TransactionType.SWAP)
            );
            Optional<AmlPatternDetectionService.DetectionResult> result =
                    AmlPatternDetectionService.detectRoundAmountRepeats(txs);
            assertTrue(result.isPresent());
            assertEquals(AmlAlert.Pattern.ROUND_AMOUNT_REPEATS, result.get().pattern());
            assertEquals(3, result.get().transactionCount());
            assertEquals(300_000L, result.get().totalAmount());
        }

        @Test
        @DisplayName("only 2 identical amounts → does NOT fire (threshold is 3)")
        void twoIdenticalDoesNotFire() {
            var txs = List.of(
                    tx(NOW.minusMinutes(30), 100_000, 95_000, TransactionType.SWAP),
                    tx(NOW.minusMinutes(10), 100_000, 95_000, TransactionType.SWAP)
            );
            assertFalse(AmlPatternDetectionService.detectRoundAmountRepeats(txs).isPresent());
        }

        @Test
        @DisplayName("3 identical amounts but spread across 2h → does NOT fire (out of window)")
        void threeIdenticalOutsideWindow() {
            var txs = List.of(
                    tx(NOW.minusHours(2), 100_000, 95_000, TransactionType.SWAP),
                    tx(NOW.minusMinutes(30), 100_000, 95_000, TransactionType.SWAP),
                    tx(NOW.minusMinutes(10), 100_000, 95_000, TransactionType.SWAP)
            );
            // Only 2 within 1h sliding window (30 + 10 minutes ago) → not enough
            assertFalse(AmlPatternDetectionService.detectRoundAmountRepeats(txs).isPresent());
        }

        @Test
        @DisplayName("4 identical amounts within 1h → fires with count=4")
        void fourIdenticalFiresCountFour() {
            var txs = List.of(
                    tx(NOW.minusMinutes(45), 50_000, 47_000, TransactionType.SWAP),
                    tx(NOW.minusMinutes(30), 50_000, 47_000, TransactionType.SWAP),
                    tx(NOW.minusMinutes(15), 50_000, 47_000, TransactionType.SWAP),
                    tx(NOW.minusMinutes(5),  50_000, 47_000, TransactionType.SWAP)
            );
            var result = AmlPatternDetectionService.detectRoundAmountRepeats(txs);
            assertTrue(result.isPresent());
            assertEquals(4, result.get().transactionCount());
            assertEquals(200_000L, result.get().totalAmount());
        }

        @Test
        @DisplayName("Mixed amounts where only one group has 3+ → fires on that group")
        void mixedAmountsFiresOnQualifyingGroup() {
            var txs = List.of(
                    tx(NOW.minusMinutes(45), 12_345, 11_000, TransactionType.SWAP),
                    tx(NOW.minusMinutes(40), 100_000, 95_000, TransactionType.SWAP),
                    tx(NOW.minusMinutes(35), 100_000, 95_000, TransactionType.SWAP),
                    tx(NOW.minusMinutes(30), 100_000, 95_000, TransactionType.SWAP),
                    tx(NOW.minusMinutes(20), 99_999, 95_000, TransactionType.SWAP)
            );
            var result = AmlPatternDetectionService.detectRoundAmountRepeats(txs);
            assertTrue(result.isPresent());
            // Should fire on the 100_000 group
            assertTrue(result.get().evidenceJson().contains("\"amount\":100000"));
        }

        @Test
        @DisplayName("empty list returns empty")
        void emptyList() {
            assertFalse(AmlPatternDetectionService.detectRoundAmountRepeats(List.of()).isPresent());
        }
    }

    @Nested
    @DisplayName("detectFastInFastOut")
    class FastInFastOutTests {

        @Test
        @DisplayName("inbound 100k → outbound 90k within 3min → fires (ratio 0.90 ≥ 0.80)")
        void fiveMinuteWindowFires() {
            var txs = List.of(
                    tx(NOW.minusMinutes(10), 0, 100_000, TransactionType.TRANSFER),  // inbound
                    tx(NOW.minusMinutes(7),  90_000, 85_000, TransactionType.SWAP)    // outbound
            );
            var result = AmlPatternDetectionService.detectFastInFastOut(txs);
            assertTrue(result.isPresent());
            assertEquals(AmlAlert.Pattern.FAST_IN_FAST_OUT, result.get().pattern());
            assertEquals(AmlAlert.Severity.HIGH, result.get().severity());
        }

        @Test
        @DisplayName("inbound 100k → outbound 70k → does NOT fire (ratio 0.70 < 0.80)")
        void belowRatioDoesNotFire() {
            var txs = List.of(
                    tx(NOW.minusMinutes(10), 0, 100_000, TransactionType.TRANSFER),
                    tx(NOW.minusMinutes(8),  70_000, 65_000, TransactionType.SWAP)
            );
            assertFalse(AmlPatternDetectionService.detectFastInFastOut(txs).isPresent());
        }

        @Test
        @DisplayName("inbound → outbound 10 minutes later → does NOT fire (outside 5min window)")
        void outsideTimeWindowDoesNotFire() {
            var txs = List.of(
                    tx(NOW.minusMinutes(20), 0, 100_000, TransactionType.TRANSFER),
                    tx(NOW.minusMinutes(8),  90_000, 85_000, TransactionType.SWAP)
            );
            assertFalse(AmlPatternDetectionService.detectFastInFastOut(txs).isPresent());
        }

        @Test
        @DisplayName("only inbound, no outbound → does NOT fire")
        void noOutboundLeg() {
            var txs = List.of(
                    tx(NOW.minusMinutes(5), 0, 100_000, TransactionType.TRANSFER)
            );
            assertFalse(AmlPatternDetectionService.detectFastInFastOut(txs).isPresent());
        }
    }

    @Nested
    @DisplayName("detectSubThresholdSplit")
    class SubThresholdSplitTests {

        @Test
        @DisplayName("3 txs summing to 580k (just under 600k) → fires")
        void justUnderThresholdFires() {
            var txs = List.of(
                    tx(NOW.minusHours(20), 200_000, 190_000, TransactionType.SWAP),
                    tx(NOW.minusHours(12), 200_000, 190_000, TransactionType.SWAP),
                    tx(NOW.minusHours(4),  180_000, 170_000, TransactionType.SWAP)
            );
            var result = AmlPatternDetectionService.detectSubThresholdSplit(txs, NOW);
            assertTrue(result.isPresent());
            assertEquals(AmlAlert.Pattern.SUB_THRESHOLD_SPLIT, result.get().pattern());
            assertEquals(3, result.get().transactionCount());
            assertEquals(580_000L, result.get().totalAmount());
        }

        @Test
        @DisplayName("sum at 700k (above threshold) → does NOT fire (normal reportable)")
        void aboveThresholdDoesNotFire() {
            var txs = List.of(
                    tx(NOW.minusHours(20), 300_000, 285_000, TransactionType.SWAP),
                    tx(NOW.minusHours(4),  400_000, 380_000, TransactionType.SWAP)
            );
            assertFalse(AmlPatternDetectionService.detectSubThresholdSplit(txs, NOW).isPresent());
        }

        @Test
        @DisplayName("sum at 400k (below floor) → does NOT fire (too small to suggest structuring)")
        void belowFloorDoesNotFire() {
            var txs = List.of(
                    tx(NOW.minusHours(10), 200_000, 190_000, TransactionType.SWAP),
                    tx(NOW.minusHours(2),  200_000, 190_000, TransactionType.SWAP)
            );
            assertFalse(AmlPatternDetectionService.detectSubThresholdSplit(txs, NOW).isPresent());
        }

        @Test
        @DisplayName("single tx of 599k → does NOT fire (not structuring, just one payment)")
        void singleLargeNotStructuring() {
            var txs = List.of(
                    tx(NOW.minusHours(2), 599_000, 590_000, TransactionType.SWAP)
            );
            assertFalse(AmlPatternDetectionService.detectSubThresholdSplit(txs, NOW).isPresent());
        }

        @Test
        @DisplayName("txs outside 24h window are excluded from aggregation")
        void outsideWindowExcluded() {
            var txs = List.of(
                    tx(NOW.minusHours(48), 500_000, 480_000, TransactionType.SWAP),
                    tx(NOW.minusHours(10), 50_000, 47_000, TransactionType.SWAP)
            );
            // Only 50k in window → below floor → no fire
            assertFalse(AmlPatternDetectionService.detectSubThresholdSplit(txs, NOW).isPresent());
        }
    }
}
