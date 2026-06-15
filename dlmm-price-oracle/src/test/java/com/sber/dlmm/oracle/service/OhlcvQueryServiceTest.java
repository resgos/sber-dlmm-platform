package com.sber.dlmm.oracle.service;

import com.sber.dlmm.oracle.dto.OhlcvCandleResponse;
import com.sber.dlmm.oracle.entity.OhlcvCandle;
import com.sber.dlmm.oracle.repository.OhlcvCandleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the read-side OHLCV interval roll-up: higher timeframes are
 * aggregated from the stored 1-minute candles on read (open=first, high=max,
 * low=min, close=last, volume/swaps=sum), UTC-bucket-aligned.
 */
class OhlcvQueryServiceTest {

    private static final UUID POOL = UUID.fromString("c0000000-0000-0000-0000-000000000101");

    private OhlcvCandleRepository repository;
    private OhlcvQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(OhlcvCandleRepository.class);
        service = new OhlcvQueryService(repository);
    }

    private static OhlcvCandle c(String time, double o, double h, double l, double cl, long vol, int sw) {
        return OhlcvCandle.builder()
                .poolId(POOL).intervalSec(60)
                .openTime(LocalDateTime.parse(time))
                .openPrice(BigDecimal.valueOf(o)).highPrice(BigDecimal.valueOf(h))
                .lowPrice(BigDecimal.valueOf(l)).closePrice(BigDecimal.valueOf(cl))
                .volumeIn(vol).swapCount(sw).build();
    }

    /** findRecent returns DESC (newest first); these span two 5-minute buckets. */
    private List<OhlcvCandle> oneMinuteDesc() {
        return List.of(
                c("2026-06-16T00:06:00", 97, 101, 96, 100, 12, 2),  // bucket 00:05
                c("2026-06-16T00:05:00", 92, 99, 88, 97, 8, 3),      // bucket 00:05
                c("2026-06-16T00:02:00", 115, 118, 90, 92, 5, 1),    // bucket 00:00
                c("2026-06-16T00:01:00", 105, 120, 100, 115, 20, 2), // bucket 00:00
                c("2026-06-16T00:00:00", 100, 110, 95, 105, 10, 1)   // bucket 00:00
        );
    }

    @Test
    void rollsUpOneMinuteCandlesIntoFiveMinuteBuckets() {
        when(repository.findRecent(eq(POOL), eq(60), any(Pageable.class))).thenReturn(oneMinuteDesc());

        List<OhlcvCandleResponse> out = service.getCandles(POOL, 300, 10);

        assertThat(out).hasSize(2);
        OhlcvCandleResponse a = out.get(0); // oldest-first → 00:00 bucket
        assertThat(a.time()).isEqualTo(LocalDateTime.parse("2026-06-16T00:00:00"));
        assertThat(a.open()).isEqualByComparingTo("100");   // first
        assertThat(a.high()).isEqualByComparingTo("120");   // max
        assertThat(a.low()).isEqualByComparingTo("90");     // min
        assertThat(a.close()).isEqualByComparingTo("92");   // last
        assertThat(a.volume()).isEqualTo(35);               // 10+20+5
        assertThat(a.swapCount()).isEqualTo(4);             // 1+2+1

        OhlcvCandleResponse b = out.get(1); // 00:05 bucket
        assertThat(b.time()).isEqualTo(LocalDateTime.parse("2026-06-16T00:05:00"));
        assertThat(b.open()).isEqualByComparingTo("92");
        assertThat(b.high()).isEqualByComparingTo("101");
        assertThat(b.low()).isEqualByComparingTo("88");
        assertThat(b.close()).isEqualByComparingTo("100");
        assertThat(b.volume()).isEqualTo(20);               // 8+12
        assertThat(b.swapCount()).isEqualTo(5);             // 3+2
    }

    @Test
    void limitKeepsTheMostRecentBuckets() {
        when(repository.findRecent(eq(POOL), eq(60), any(Pageable.class))).thenReturn(oneMinuteDesc());
        List<OhlcvCandleResponse> out = service.getCandles(POOL, 300, 1);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).time()).isEqualTo(LocalDateTime.parse("2026-06-16T00:05:00")); // most recent
    }

    @Test
    void nonMultipleOfBaseReturnsEmpty() {
        assertThat(service.getCandles(POOL, 90, 10)).isEmpty();   // 90 % 60 != 0
        assertThat(service.getCandles(POOL, 0, 10)).isEmpty();
    }

    @Test
    void baseIntervalPassesThroughReversedToAscending() {
        when(repository.findRecent(eq(POOL), eq(60), any(Pageable.class))).thenReturn(oneMinuteDesc());
        List<OhlcvCandleResponse> out = service.getCandles(POOL, 60, 10);
        assertThat(out).hasSize(5);
        assertThat(out.get(0).time()).isEqualTo(LocalDateTime.parse("2026-06-16T00:00:00")); // oldest first
        assertThat(out.get(4).time()).isEqualTo(LocalDateTime.parse("2026-06-16T00:06:00"));
    }
}
