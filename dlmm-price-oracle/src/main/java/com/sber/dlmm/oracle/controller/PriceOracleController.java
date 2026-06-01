package com.sber.dlmm.oracle.controller;

import com.sber.dlmm.oracle.dto.CbrSpreadResponse;
import com.sber.dlmm.oracle.dto.OhlcvCandleResponse;
import com.sber.dlmm.oracle.dto.PriceFeedResponse;
import com.sber.dlmm.oracle.dto.TwapResponse;
import com.sber.dlmm.oracle.service.OhlcvQueryService;
import com.sber.dlmm.oracle.service.PriceOracleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/**
 * REST API for the price-oracle service — the read-facing HTTP layer over
 * {@link PriceOracleService} (price feeds, TWAP, CBR spread) and
 * {@link OhlcvQueryService} (pool chart candles). All routes are mounted
 * under {@code /api/v1/oracle} and reached via the gateway.
 *
 * <p>Responsibility split: this controller does no business logic of its own.
 * It validates path/query shape (Spring binding), delegates to a service, and
 * wraps the result in a {@link ResponseEntity}. The interesting cross-cutting
 * behaviour lives in the services:
 * <ul>
 *   <li>Staleness → HTTP 503: {@code /price} and {@code /twap} throw
 *       {@code OracleUnavailableException} (rendered 503) when the underlying
 *       feed is missing or its last update is older than the staleness
 *       threshold, so callers never trade on a frozen price.</li>
 *   <li>Empty-list-not-404: {@code /prices} and {@code /ohlcv} return an empty
 *       collection (HTTP 200) rather than an error when there is no data, since
 *       the chart and dashboard treat "no rows yet" as a normal warm-up state.</li>
 * </ul>
 *
 * <p>Swagger/OpenAPI annotations on each handler are the source for the
 * generated API docs; the Javadoc here is the developer-facing complement and
 * deliberately sits ABOVE the Swagger annotations.
 */
@RestController
@RequestMapping("/api/v1/oracle")
@RequiredArgsConstructor
@Tag(name = "Price Oracle", description = "Asset price feeds, TWAP, CBR market spread, and OHLCV candle series for pool charts")
public class PriceOracleController {

    private final PriceOracleService priceOracleService;
    private final OhlcvQueryService ohlcvQueryService;

    /**
     * Returns the latest spot price feed for a single asset.
     *
     * <p>Why it can fail: the oracle must not let callers act on a frozen
     * price, so the service rejects an unknown symbol or a stale feed with a
     * 503 instead of returning a misleading number.
     *
     * @param symbol asset symbol, case-insensitive (e.g. {@code SBER},
     *               {@code SBTC}, {@code SUSDT})
     * @return HTTP 200 with the current feed (spot, TWAP, 24h change, source,
     *         last-updated)
     * @throws com.sber.dlmm.common.exception.OracleUnavailableException if no
     *         feed exists for the symbol or its data is stale (rendered as 503)
     */
    @GetMapping("/price/{symbol}")
    @Operation(summary = "Get the latest spot price for an asset",
            description = "Returns the current price feed (spot price, TWAP, 24h change, source, last-updated) "
                    + "for the given asset symbol. The lookup is case-insensitive.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current price feed for the asset"),
            @ApiResponse(responseCode = "503", description = "No price feed exists for the symbol, or its data is stale (last update older than the staleness threshold)")
    })
    public ResponseEntity<PriceFeedResponse> getPrice(
            @Parameter(description = "Asset symbol, case-insensitive (e.g. SBER, SBTC, SUSDT)") @PathVariable String symbol) {
        PriceFeedResponse response = priceOracleService.getPrice(symbol);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the time-weighted average price for an asset over a trailing
     * window.
     *
     * <p>Why TWAP and not spot: a window-averaged price is harder to
     * manipulate with a single print, which is what downstream consumers
     * (e.g. margin / valuation checks) want. The same staleness guard as
     * {@code /price} applies — a frozen feed must not be averaged into a
     * confident-looking number.
     *
     * @param symbol asset symbol, case-insensitive (e.g. {@code SBER},
     *               {@code SBTC}, {@code SUSDT})
     * @param period TWAP window in minutes; non-positive values fall back to
     *               the 15-minute default
     * @return HTTP 200 with the averaged price for the requested window,
     *         falling back to current spot when no history exists in the window
     * @throws com.sber.dlmm.common.exception.OracleUnavailableException if no
     *         feed exists for the symbol or its data is stale (rendered as 503)
     */
    @GetMapping("/twap/{symbol}")
    @Operation(summary = "Get the time-weighted average price (TWAP) for an asset",
            description = "Returns the arithmetic mean of recorded prices over the trailing window for the given "
                    + "asset symbol. Falls back to the current spot price when no history exists in the window.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "TWAP price for the asset over the requested window"),
            @ApiResponse(responseCode = "503", description = "No price feed exists for the symbol, or its data is stale")
    })
    public ResponseEntity<TwapResponse> getTwapPrice(
            @Parameter(description = "Asset symbol, case-insensitive (e.g. SBER, SBTC, SUSDT)") @PathVariable String symbol,
            @Parameter(description = "TWAP window in minutes; non-positive values fall back to the 15-minute default")
            @RequestParam(defaultValue = "15") int period) {
        BigDecimal twapPrice = priceOracleService.getTwapPrice(symbol, period);
        return ResponseEntity.ok(new TwapResponse(symbol.toUpperCase(), twapPrice, period));
    }

    /**
     * Lists the latest feed for every tracked asset in one call.
     *
     * <p>Why no staleness gate here (unlike {@code /price}): this is a
     * dashboard/overview endpoint, so a stale entry is shown with its
     * last-updated timestamp rather than failing the whole list. An empty
     * list (not a 404) is returned when no feeds have been recorded yet.
     *
     * @return HTTP 200 with all known price feeds, possibly empty
     */
    @GetMapping("/prices")
    @Operation(summary = "List the latest price feeds for all tracked assets",
            description = "Returns every known price feed (spot price, TWAP, 24h change, source, last-updated). "
                    + "Returns an empty list when no feeds have been recorded yet.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "All known asset price feeds")
    })
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
    @Operation(summary = "Get the DLMM market vs CBR official rate spread for a currency",
            description = "Returns the DLMM market rate, the Bank of Russia (CBR) official rate, and the signed "
                    + "spread in basis points (positive = DLMM above official, negative = below). The spread is "
                    + "null when either side is missing (CBR fetch not yet run, or the currency is absent from the "
                    + "DLMM catalog). Powers the admin-dashboard \"spread vs official\" tile.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Spread payload; rate/spread fields are null when a side is unavailable")
    })
    public ResponseEntity<CbrSpreadResponse> getCbrSpread(
            @Parameter(description = "Currency symbol, case-insensitive (e.g. USD, EUR, CNY)") @PathVariable String currency) {
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
    @Operation(summary = "Get OHLCV candle series for a pool",
            description = "Returns up to limit most-recent OHLCV candles for the pool at the requested interval, "
                    + "ordered oldest-first (TradingView convention) for the pool detail price chart. Only the "
                    + "1-minute (60s) interval is supported today; other intervals return an empty list. An unknown "
                    + "pool also returns an empty list.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Candle series oldest-first; empty when the interval is unsupported or the pool has no candles")
    })
    public ResponseEntity<List<OhlcvCandleResponse>> getOhlcv(
            @Parameter(description = "Pool identifier (UUID)") @PathVariable UUID poolId,
            @Parameter(description = "Candle bucket width in seconds; only 60 (1-minute) is supported today")
            @RequestParam(defaultValue = "60") int interval,
            @Parameter(description = "Maximum number of candles to return; clamped to the range 1..500")
            @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(ohlcvQueryService.getCandles(poolId, interval, limit));
    }
}
