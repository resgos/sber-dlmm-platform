package com.sber.dlmm.transaction.service;

import com.sber.dlmm.transaction.entity.AmlAlert;
import com.sber.dlmm.transaction.entity.Transaction;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sprint 6 #6.9 — AML pattern detectors. Pure logic on transaction lists,
 * exposed as static methods so unit tests don't need Spring + JPA.
 *
 * <p>Three detectors:
 * <ol>
 *   <li>{@link #detectRoundAmountRepeats} — ≥3 transactions with identical
 *       amountIn within 1h (structuring proxy)</li>
 *   <li>{@link #detectFastInFastOut} — TRANSFER inbound followed by
 *       outbound transfer within 5min, ≥80% of inbound amount
 *       (laundering proxy)</li>
 *   <li>{@link #detectSubThresholdSplit} — 24h aggregate amountIn between
 *       500k and 600k ₽ (just under 115-ФЗ reporting threshold)</li>
 * </ol>
 *
 * <p>Each detector returns {@link Optional#empty()} if no pattern fires.
 * Caller (scheduler) persists the alert + emits compliance event.
 */
@Slf4j
public final class AmlPatternDetectionService {

    private static final Duration ROUND_AMOUNT_WINDOW = Duration.ofHours(1);
    private static final int ROUND_AMOUNT_MIN_REPEATS = 3;

    private static final Duration FAST_IO_WINDOW = Duration.ofMinutes(5);
    private static final double FAST_IO_RATIO_THRESHOLD = 0.80;

    private static final Duration SPLIT_WINDOW = Duration.ofHours(24);
    private static final long SPLIT_FLOOR = 500_000L;
    private static final long SPLIT_CEIL = 600_000L; // 115-ФЗ reportable threshold

    private AmlPatternDetectionService() {}

    /**
     * Detects ≥{@link #ROUND_AMOUNT_MIN_REPEATS} transactions with identical
     * {@code amountIn} within {@link #ROUND_AMOUNT_WINDOW}. Returns the
     * latest matching cluster's evidence. Single-user input.
     *
     * <p>Caller should pre-filter to a single user's transactions ordered
     * by createdAt ascending.
     */
    public static Optional<DetectionResult> detectRoundAmountRepeats(List<Transaction> userTxs) {
        if (userTxs.size() < ROUND_AMOUNT_MIN_REPEATS) return Optional.empty();

        // Group by amountIn → list of timestamps, find any group where
        // ≥3 timestamps fall within a 1h sliding window.
        Map<Long, List<LocalDateTime>> byAmount = new HashMap<>();
        for (Transaction tx : userTxs) {
            if (tx.getAmountIn() == null || tx.getAmountIn() <= 0) continue;
            byAmount.computeIfAbsent(tx.getAmountIn(), k -> new ArrayList<>())
                    .add(tx.getCreatedAt());
        }

        for (Map.Entry<Long, List<LocalDateTime>> e : byAmount.entrySet()) {
            List<LocalDateTime> times = e.getValue();
            if (times.size() < ROUND_AMOUNT_MIN_REPEATS) continue;
            times.sort(Comparator.naturalOrder());
            // Sliding window: for each starting index, check if there are
            // at least MIN_REPEATS items within the window starting there.
            for (int i = 0; i <= times.size() - ROUND_AMOUNT_MIN_REPEATS; i++) {
                LocalDateTime windowEnd = times.get(i).plus(ROUND_AMOUNT_WINDOW);
                int count = 0;
                for (int j = i; j < times.size(); j++) {
                    if (!times.get(j).isAfter(windowEnd)) count++;
                    else break;
                }
                if (count >= ROUND_AMOUNT_MIN_REPEATS) {
                    return Optional.of(new DetectionResult(
                            AmlAlert.Pattern.ROUND_AMOUNT_REPEATS,
                            AmlAlert.Severity.MEDIUM,
                            times.get(i),
                            times.get(i + count - 1),
                            count,
                            e.getKey() * count,
                            String.format("{\"amount\":%d,\"count\":%d}", e.getKey(), count)
                    ));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Detects an inbound TRANSFER followed by an outbound TRANSFER from
     * the same user within {@link #FAST_IO_WINDOW}, where the outbound
     * amount ≥ {@link #FAST_IO_RATIO_THRESHOLD} of inbound. Single-user input.
     *
     * <p>For prototype, "inbound" = TRANSFER with non-null amountOut (we don't
     * distinguish deposit vs withdrawal in TRANSFER yet); "outbound" =
     * SWAP, TRANSFER, REMOVE_LIQUIDITY immediately following.
     */
    public static Optional<DetectionResult> detectFastInFastOut(List<Transaction> userTxs) {
        for (int i = 0; i < userTxs.size(); i++) {
            Transaction in = userTxs.get(i);
            // Treat any inbound credit-like tx as the "in" leg.
            if (in.getAmountOut() == null || in.getAmountOut() <= 0) continue;
            long inAmount = in.getAmountOut();
            for (int j = i + 1; j < userTxs.size(); j++) {
                Transaction out = userTxs.get(j);
                if (out.getAmountIn() == null || out.getAmountIn() <= 0) continue;
                Duration delta = Duration.between(in.getCreatedAt(), out.getCreatedAt());
                if (delta.compareTo(FAST_IO_WINDOW) > 0) break; // outside window
                double ratio = (double) out.getAmountIn() / inAmount;
                if (ratio >= FAST_IO_RATIO_THRESHOLD) {
                    return Optional.of(new DetectionResult(
                            AmlAlert.Pattern.FAST_IN_FAST_OUT,
                            AmlAlert.Severity.HIGH,
                            in.getCreatedAt(),
                            out.getCreatedAt(),
                            2,
                            inAmount,
                            String.format("{\"inTxId\":\"%s\",\"outTxId\":\"%s\",\"ratio\":%.3f}",
                                    in.getId(), out.getId(), ratio)
                    ));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Detects 24h aggregate {@code amountIn} between {@link #SPLIT_FLOOR}
     * and {@link #SPLIT_CEIL} (just under 115-ФЗ 600k ₽ reporting
     * threshold) where the user made multiple smaller transactions —
     * structuring proxy. Single-user input.
     */
    public static Optional<DetectionResult> detectSubThresholdSplit(List<Transaction> userTxs, LocalDateTime now) {
        LocalDateTime windowStart = now.minus(SPLIT_WINDOW);
        long sum = 0;
        int count = 0;
        LocalDateTime earliest = null;
        LocalDateTime latest = null;
        for (Transaction tx : userTxs) {
            if (tx.getCreatedAt().isBefore(windowStart)) continue;
            if (tx.getAmountIn() == null || tx.getAmountIn() <= 0) continue;
            sum += tx.getAmountIn();
            count++;
            if (earliest == null || tx.getCreatedAt().isBefore(earliest)) earliest = tx.getCreatedAt();
            if (latest == null || tx.getCreatedAt().isAfter(latest)) latest = tx.getCreatedAt();
        }
        // Detection condition: in the 500k-600k bracket AND at least 2 transactions
        // (single 599_999₽ payment isn't structuring — it's just a payment).
        if (count >= 2 && sum >= SPLIT_FLOOR && sum < SPLIT_CEIL) {
            return Optional.of(new DetectionResult(
                    AmlAlert.Pattern.SUB_THRESHOLD_SPLIT,
                    AmlAlert.Severity.HIGH,
                    earliest, latest,
                    count, sum,
                    String.format("{\"sum\":%d,\"count\":%d,\"threshold\":%d}",
                            sum, count, SPLIT_CEIL)
            ));
        }
        return Optional.empty();
    }

    /**
     * Detector result — caller (AmlScannerScheduler) translates to
     * a persisted {@link AmlAlert} row + outbox event.
     */
    public record DetectionResult(
            AmlAlert.Pattern pattern,
            AmlAlert.Severity severity,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            int transactionCount,
            long totalAmount,
            String evidenceJson
    ) {}
}
