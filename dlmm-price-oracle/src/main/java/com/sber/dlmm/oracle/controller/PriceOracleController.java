package com.sber.dlmm.oracle.controller;

import com.sber.dlmm.oracle.dto.CbrSpreadResponse;
import com.sber.dlmm.oracle.dto.OhlcvCandleResponse;
import com.sber.dlmm.oracle.dto.PriceFeedResponse;
import com.sber.dlmm.oracle.dto.TwapResponse;
import com.sber.dlmm.oracle.service.OhlcvQueryService;
import com.sber.dlmm.oracle.service.PriceOracleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/oracle")
@RequiredArgsConstructor
public class PriceOracleController {

    private final PriceOracleService priceOracleService;
    private final OhlcvQueryService ohlcvQueryService;

    @GetMapping("/price/{symbol}")
    public ResponseEntity<PriceFeedResponse> getPrice(@PathVariable String symbol) {
        PriceFeedResponse response = priceOracleService.getPrice(symbol);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/twap/{symbol}")
    public ResponseEntity<TwapResponse> getTwapPrice(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "15") int period) {
        BigDecimal twapPrice = priceOracleService.getTwapPrice(symbol, period);
        return ResponseEntity.ok(new TwapResponse(symbol.toUpperCase(), twapPrice, period));
    }

    @GetMapping("/prices")
    public ResponseEntity<List<PriceFeedResponse>> getAllPrices() {
        List<PriceFeedResponse> prices = priceOracleService.getAllPrices();
        return ResponseEntity.ok(prices);
    }

    /**
     * Sprint 5 #5.9 — DLMM market rate vs CBR (Bank of Russia) official
     * rate for the given currency (USD / EUR / CNY / ...). Powers the
     * admin-dashboard "spread vs official" tile.
     *
     * <p>Spread reported in signed basis points: positive = DLMM market
     * is ABOVE official (DLMM USD costs more rubles), negative = below.
     * Null spread = one side missing (CBR fetch hasn't run yet, or DLMM
     * doesn't have the currency in catalog).
     */
    @GetMapping("/spread/{currency}")
    public ResponseEntity<CbrSpreadResponse> getCbrSpread(@PathVariable String currency) {
        return ResponseEntity.ok(priceOracleService.getCbrSpread(currency));
    }

    /**
     * Sprint 9-DS-r4 (P1-11) — OHLCV candle series for the
     * TradingView-style chart on PoolDetailPage (P1-4). Source data is
     * the {@code pool-events} Kafka stream; aggregator buckets per
     * minute and flushes to Postgres every 30s. Read endpoint clamps
     * limit to 500 and accepts only the 1-minute interval today
     * (higher intervals are a Sprint 10 roll-up).
     *
     * <p>{@code interval=60} is the bucket width in seconds; the
     * default mirrors lightweight-charts' typical 1m candle granularity.
     *
     * @return candles oldest-first (TradingView convention)
     */
    @GetMapping("/ohlcv/{poolId}")
    public ResponseEntity<List<OhlcvCandleResponse>> getOhlcv(
            @PathVariable UUID poolId,
            @RequestParam(defaultValue = "60") int interval,
            @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(ohlcvQueryService.getCandles(poolId, interval, limit));
    }
}
