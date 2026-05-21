package com.sber.dlmm.oracle.service;

import com.sber.dlmm.oracle.entity.OhlcvCandle;
import com.sber.dlmm.oracle.repository.OhlcvCandleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 9-DS-r4 (P1-11) — pins the in-memory bucketing + flush
 * contract of {@link OhlcvAggregator}.
 */
class OhlcvAggregatorTest {

    private OhlcvCandleRepository repository;
    private OhlcvAggregator aggregator;

    @BeforeEach
    void setUp() {
        repository = mock(OhlcvCandleRepository.class);
        when(repository.findByPoolIdAndIntervalSecAndOpenTime(any(), anyIntValue(), any()))
                .thenReturn(Optional.empty());
        aggregator = new OhlcvAggregator(repository);
    }

    @Test
    void singleSwap_creates_one_bucket_with_OHLC_all_equal_to_price() {
        UUID pool = UUID.randomUUID();
        long t = 1_700_000_000L; // arbitrary epoch sec in 2023
        aggregator.record(pool, t, new BigDecimal("100"), 1_000L);

        Map<OhlcvAggregator.BucketKey, OhlcvAggregator.Bucket> snap = aggregator.snapshot();
        assertThat(snap).hasSize(1);
        OhlcvAggregator.Bucket b = snap.values().iterator().next();
        assertThat(b.open()).isEqualByComparingTo("100");
        assertThat(b.high()).isEqualByComparingTo("100");
        assertThat(b.low()).isEqualByComparingTo("100");
        assertThat(b.close()).isEqualByComparingTo("100");
        assertThat(b.volumeIn()).isEqualTo(1_000L);
        assertThat(b.swapCount()).isEqualTo(1);
    }

    @Test
    void multiple_swaps_in_same_minute_accumulate_into_one_bucket_with_correct_OHLC() {
        UUID pool = UUID.randomUUID();
        long base = 1_700_000_000L;
        // Three ticks in the same minute. Open=100, high=120, low=90, close=110.
        aggregator.record(pool, base, new BigDecimal("100"), 1_000L);
        aggregator.record(pool, base + 10, new BigDecimal("120"), 2_000L);
        aggregator.record(pool, base + 20, new BigDecimal("90"), 500L);
        aggregator.record(pool, base + 30, new BigDecimal("110"), 1_500L);

        Map<OhlcvAggregator.BucketKey, OhlcvAggregator.Bucket> snap = aggregator.snapshot();
        assertThat(snap).hasSize(1);
        OhlcvAggregator.Bucket b = snap.values().iterator().next();
        assertThat(b.open()).isEqualByComparingTo("100");
        assertThat(b.high()).isEqualByComparingTo("120");
        assertThat(b.low()).isEqualByComparingTo("90");
        assertThat(b.close()).isEqualByComparingTo("110");
        assertThat(b.volumeIn()).isEqualTo(5_000L);
        assertThat(b.swapCount()).isEqualTo(4);
    }

    @Test
    void swaps_across_two_minutes_create_two_buckets() {
        UUID pool = UUID.randomUUID();
        long m1 = 1_700_000_000L;
        long m2 = m1 + 60;
        aggregator.record(pool, m1, new BigDecimal("100"), 100L);
        aggregator.record(pool, m2, new BigDecimal("105"), 200L);

        assertThat(aggregator.snapshot()).hasSize(2);
    }

    @Test
    void flush_persists_only_sealed_buckets() {
        UUID pool = UUID.randomUUID();
        long pastMinute = ((System.currentTimeMillis() / 1000L) / 60) * 60 - 120; // 2 min ago
        long currentMinute = ((System.currentTimeMillis() / 1000L) / 60) * 60;

        aggregator.record(pool, pastMinute, new BigDecimal("100"), 1L);
        aggregator.record(pool, currentMinute, new BigDecimal("200"), 1L);

        aggregator.flush();

        // The sealed (past) bucket persists; the in-progress current
        // one stays in memory.
        ArgumentCaptor<OhlcvCandle> captor = ArgumentCaptor.forClass(OhlcvCandle.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getOpenPrice()).isEqualByComparingTo("100");
        assertThat(aggregator.snapshot()).hasSize(1);
        assertThat(aggregator.snapshot().values().iterator().next().open())
                .isEqualByComparingTo("200");
    }

    @Test
    void flush_with_no_sealed_buckets_does_nothing() {
        UUID pool = UUID.randomUUID();
        long currentMinute = ((System.currentTimeMillis() / 1000L) / 60) * 60;
        aggregator.record(pool, currentMinute, new BigDecimal("100"), 1L);

        aggregator.flush();

        verify(repository, never()).save(any());
        assertThat(aggregator.snapshot()).hasSize(1);
    }

    @Test
    void zero_or_negative_price_is_ignored() {
        UUID pool = UUID.randomUUID();
        aggregator.record(pool, Instant.now().getEpochSecond(), BigDecimal.ZERO, 1_000L);
        aggregator.record(pool, Instant.now().getEpochSecond(), new BigDecimal("-1"), 1_000L);
        aggregator.record(pool, Instant.now().getEpochSecond(), null, 1_000L);

        assertThat(aggregator.snapshot()).isEmpty();
    }

    // Helper to dodge a Mockito argument-type ambiguity (anyInt() vs anyIntValue)
    private static int anyIntValue() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
