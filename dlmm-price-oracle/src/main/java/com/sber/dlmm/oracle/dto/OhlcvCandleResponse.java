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
