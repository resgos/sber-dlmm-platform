package com.sber.dlmm.oracle.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sprint 9-DS-r4 (P1-11) — OHLCV serialisation shape for the
 * TradingView-style chart on PoolDetailPage. Field names match
 * lightweight-charts' candlestick series expectation
 * (time / open / high / low / close), with {@code volume} as an
 * extension for the optional volume sub-chart.
 *
 * <p>{@code time} is ISO-8601 local time — the FE converts to UTC
 * seconds before handing to lightweight-charts. Keeping it as a
 * string avoids epoch-ms ambiguity in the JSON.
 *
 * @param time      bucket open time (candle timestamp), serialised as ISO-8601 local time
 * @param open      opening price of the bucket (first swap's execution price, quote-per-base ratio)
 * @param high      highest execution price within the bucket
 * @param low       lowest execution price within the bucket
 * @param close     closing price of the bucket (last swap's execution price, quote-per-base ratio)
 * @param volume    total traded volume in the bucket, in base units (sum of swap amount-in)
 * @param swapCount number of swaps aggregated into the bucket
 */
public record OhlcvCandleResponse(
        LocalDateTime time,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        int swapCount
) {
}
