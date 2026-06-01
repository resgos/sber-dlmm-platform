package com.sber.dlmm.oracle.service;

import com.sber.dlmm.common.exception.OracleUnavailableException;
import com.sber.dlmm.oracle.client.MarketDataClient;
import com.sber.dlmm.oracle.dto.MarketQuote;
import com.sber.dlmm.oracle.dto.PriceFeedResponse;
import com.sber.dlmm.oracle.entity.PriceFeed;
import com.sber.dlmm.oracle.entity.PriceHistory;
import com.sber.dlmm.oracle.repository.PriceFeedRepository;
import com.sber.dlmm.oracle.repository.PriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core pricing engine of the oracle: maintains the {@code price_feeds} table,
 * its rolling {@code price_history}, and answers spot / TWAP / spread queries.
 *
 * <p>It blends three price sources into one uniform feed shape:
 * <ul>
 *   <li><b>Real external markets</b> ({@link #syncRealMarketPrices}, every 2
 *       min) — crypto via CoinGecko, FX and precious metals via the Bank of
 *       Russia, fetched through {@link MarketDataClient}. These are the
 *       genuine "биржевые цены" surfaced in the UI.</li>
 *   <li><b>Synthetic walk</b> ({@link #fetchPricesFromMoex}, every 15 s) — a
 *       labelled ±2% random walk for MOEX equities/indices whose real ISS feed
 *       is geo-blocked from this environment (anchored on {@link #INITIAL_PRICES}).</li>
 *   <li><b>CBR official daily fixing</b> — written separately by
 *       {@code CbrRatesService} under {@code CBR-*} symbols; consumed here only
 *       to compute the market-vs-official spread ({@link #getCbrSpread}).</li>
 * </ul>
 *
 * <p>Key invariants:
 * <ul>
 *   <li><b>Staleness → 503:</b> {@link #getPrice} and {@link #getTwapPrice}
 *       refuse to serve a feed whose last update is older than
 *       {@link #STALENESS_THRESHOLD_MS} (5 min), throwing
 *       {@link OracleUnavailableException} so callers never trade on a frozen
 *       price.</li>
 *   <li><b>TWAP = mean of ticks:</b> the time-weighted price is the arithmetic
 *       mean of {@code price_history} rows inside the trailing window
 *       ({@link #TWAP_DEFAULT_PERIOD_MINUTES}); it falls back to the current
 *       spot when the window holds no ticks.</li>
 *   <li><b>Per-feed upsert:</b> a feed is identified by its (upper-cased)
 *       asset symbol and updated in place, so each scheduler only ever owns the
 *       symbols it writes — they never collide.</li>
 * </ul>
 *
 * <p>All money math uses {@link #MC} (10 significant digits, HALF_UP) to keep
 * ratios deterministic and avoid non-terminating {@link BigDecimal} divisions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PriceOracleService {

    /** Max age of a feed before {@link #getPrice}/{@link #getTwapPrice} treat it as stale and 503. */
    private static final long STALENESS_THRESHOLD_MS = 5 * 60 * 1000L;
    /** Default TWAP window (minutes) when the caller passes a non-positive period. */
    private static final int TWAP_DEFAULT_PERIOD_MINUTES = 15;
    /** Shared rounding context (10 significant digits, HALF_UP) for all price/ratio math. */
    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

    // Equity feeds — anchored on rough MOEX spot at branch time.
    // Real MOEX integration ships in Sprint 4 once procurement of
    // ISS API access lands (tracked separately as R#11).
    private static final Map<String, BigDecimal> INITIAL_PRICES = Map.ofEntries(
            Map.entry("SBER", new BigDecimal("280.50")),
            Map.entry("GAZP", new BigDecimal("165.20")),
            Map.entry("LKOH", new BigDecimal("7450.00")),
            Map.entry("YNDX", new BigDecimal("3200.00")),
            Map.entry("GMKN", new BigDecimal("15800.00")),
            Map.entry("ROSN", new BigDecimal("550.30")),
            Map.entry("VTBR", new BigDecimal("0.0225")),
            Map.entry("MGNT", new BigDecimal("5400.00")),
            // Sprint 3 #3.5 — FX feeds required by USE-CASE-FX-HEDGE.md.
            // Quoted as "1 unit of FX = N RUB" (the price of the foreign
            // currency in rubles). FX hedge UI in Sprint 4 reads these
            // to compute the SRUB/SCNY, SRUB/SUSDT, SRUB/SEUR pool quotes.
            // Volatility envelope on these is the same ±2%/15s random
            // walk as equity — fine for demo, replaced by MOEX FX feed
            // (different endpoint than ISS equity) in Sprint 4.
            Map.entry("CNY", new BigDecimal("12.50")),
            Map.entry("USD", new BigDecimal("80.00")),
            Map.entry("EUR", new BigDecimal("95.00")),
            // Sprint 16 — extra MOEX equities/indices on the synthetic walk (MOEX ISS
            // is geo-blocked from this env → no real feed; anchored on seed spot).
            Map.entry("TATN", new BigDecimal("720.00")),
            Map.entry("NLMK", new BigDecimal("189.30")),
            Map.entry("SMOEX", new BigDecimal("3215.00")),
            Map.entry("SRTSI", new BigDecimal("1098.50")),
            Map.entry("SOIL", new BigDecimal("6800.00")),
            Map.entry("SNGAS", new BigDecimal("3540.00"))
    );

    // ── Real-market source maps (free APIs reachable from this environment) ──
    /** platform symbol → CoinGecko coin id (priced in RUB). */
    private static final Map<String, String> CRYPTO_COINGECKO = Map.of(
            "SBTC", "bitcoin",
            "SETH", "ethereum");
    /** platform token → CBR FX char-code (RUB per 1 unit). USDT≈USD official rate. */
    private static final Map<String, String> FX_CBR = Map.of(
            "SUSDT", "USD",
            "SEUR", "EUR",
            "SCNY", "CNY");
    /** platform token → CBR precious-metal code (1=gold,2=silver,3=platinum,4=palladium), RUB/gram. */
    private static final Map<String, Integer> METALS_CBR = Map.of(
            "SGOLD", 1,
            "SSILV", 2,
            "SPLAT", 3,
            "SPALD", 4);

    private final PriceFeedRepository priceFeedRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final MarketDataClient marketDataClient;
    private final Random random = new Random();
    private final ConcurrentHashMap<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

    /**
     * Returns the current spot feed for a symbol, guarding against stale data.
     *
     * <p>WHY the staleness check: a price that stopped updating (dead feed,
     * crashed scheduler) is more dangerous than no price at all, so we fail
     * loudly with a 503 instead of returning a frozen number.
     *
     * @param assetSymbol asset symbol; matched case-insensitively (upper-cased
     *                    before lookup)
     * @return the feed mapped to a {@link PriceFeedResponse}
     * @throws OracleUnavailableException if no feed exists for the symbol, or
     *                                    the feed is older than the staleness
     *                                    threshold
     */
    @Transactional(readOnly = true)
    public PriceFeedResponse getPrice(String assetSymbol) {
        PriceFeed feed = priceFeedRepository.findByAssetSymbol(assetSymbol.toUpperCase())
                .orElseThrow(() -> new OracleUnavailableException(
                        "Price feed not found for symbol: " + assetSymbol));

        checkStaleness(feed);
        return toResponse(feed);
    }

    /**
     * Computes the time-weighted average price over a trailing window as the
     * arithmetic mean of recorded history ticks.
     *
     * <p>WHY a simple mean (not true integral-over-time TWAP): ticks are
     * recorded at a roughly fixed cadence by the schedulers, so an unweighted
     * average of the window's ticks is a faithful and cheaper approximation.
     * When the window holds no ticks (e.g. a brand-new feed) we fall back to
     * the current spot so the caller still gets a usable number.
     *
     * @param assetSymbol   asset symbol; matched case-insensitively
     * @param periodMinutes trailing window in minutes; values {@code <= 0} fall
     *                      back to {@link #TWAP_DEFAULT_PERIOD_MINUTES}
     * @return the mean price over the window, or the current spot if the window
     *         is empty
     * @throws OracleUnavailableException if no feed exists for the symbol, or
     *                                    the feed is stale
     */
    @Transactional(readOnly = true)
    public BigDecimal getTwapPrice(String assetSymbol, int periodMinutes) {
        int period = periodMinutes > 0 ? periodMinutes : TWAP_DEFAULT_PERIOD_MINUTES;
        PriceFeed feed = priceFeedRepository.findByAssetSymbol(assetSymbol.toUpperCase())
                .orElseThrow(() -> new OracleUnavailableException(
                        "Price feed not found for symbol: " + assetSymbol));

        checkStaleness(feed);

        long fromTimestamp = System.currentTimeMillis() - (long) period * 60 * 1000;
        List<PriceHistory> histories = priceHistoryRepository
                .findByPriceFeedIdAndTimestampEpochMsGreaterThanEqual(feed.getId(), fromTimestamp);

        if (histories.isEmpty()) {
            return feed.getCurrentPrice();
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (PriceHistory history : histories) {
            sum = sum.add(history.getPrice());
        }
        return sum.divide(BigDecimal.valueOf(histories.size()), MC);
    }

    /**
     * Returns every known feed for dashboard/overview use.
     *
     * <p>WHY no staleness gate: this is a bulk listing, so a stale entry is
     * surfaced with its last-updated timestamp rather than failing the whole
     * response; consumers decide per-row how to treat age.
     *
     * @return all feeds mapped to {@link PriceFeedResponse}, possibly empty
     */
    @Transactional(readOnly = true)
    public List<PriceFeedResponse> getAllPrices() {
        return priceFeedRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Sprint 5 #5.9 — DLMM market vs CBR official spread for a single
     * currency. Returns null if either side is missing (CBR fetch
     * hasn't run yet, or DLMM doesn't have the currency in catalog).
     * Caller (admin-bff) treats null as "show — instead of a number".
     *
     * <p>Currency lookup is by symbol: DLMM market is e.g. "USD",
     * "EUR", "CNY" (matches the existing mock feed symbols); CBR side
     * is prefixed "CBR-USD" etc.
     *
     * @param currency currency symbol, case-insensitive (e.g. {@code USD},
     *                 {@code EUR}, {@code CNY})
     * @return a spread payload; the {@code spread} field (and the missing
     *         side's rate) is {@code null} when either the DLMM or CBR feed is
     *         absent
     */
    @Transactional(readOnly = true)
    public com.sber.dlmm.oracle.dto.CbrSpreadResponse getCbrSpread(String currency) {
        String upper = currency.toUpperCase();
        PriceFeed dlmm = priceFeedRepository.findByAssetSymbol(upper).orElse(null);
        PriceFeed cbr = priceFeedRepository.findByAssetSymbol("CBR-" + upper).orElse(null);
        if (dlmm == null || cbr == null) {
            return new com.sber.dlmm.oracle.dto.CbrSpreadResponse(
                    upper,
                    dlmm == null ? null : dlmm.getCurrentPrice(),
                    cbr == null ? null : cbr.getCurrentPrice(),
                    null,
                    dlmm == null ? null : dlmm.getUpdatedAt(),
                    cbr == null ? null : cbr.getUpdatedAt());
        }
        BigDecimal spread = computeSpreadBps(dlmm.getCurrentPrice(), cbr.getCurrentPrice());
        return new com.sber.dlmm.oracle.dto.CbrSpreadResponse(
                upper,
                dlmm.getCurrentPrice(),
                cbr.getCurrentPrice(),
                spread,
                dlmm.getUpdatedAt(),
                cbr.getUpdatedAt());
    }

    /**
     * Signed spread in basis points (100 bps = 1%). Positive =
     * DLMM market above CBR official, negative = below. Visible
     * for unit testing.
     *
     * @param dlmmRate the DLMM market rate (numerator side)
     * @param cbrRate  the CBR official rate (denominator/reference side)
     * @return the signed spread in basis points rounded to 2 dp, or
     *         {@code null} when {@code cbrRate} is null or non-positive (can't
     *         divide by it)
     */
    static BigDecimal computeSpreadBps(BigDecimal dlmmRate, BigDecimal cbrRate) {
        if (cbrRate == null || cbrRate.compareTo(BigDecimal.ZERO) <= 0) return null;
        return dlmmRate.subtract(cbrRate)
                .divide(cbrRate, MC)
                .multiply(BigDecimal.valueOf(10_000))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Sprint 16 — REAL market prices from free public APIs: crypto via CoinGecko,
     * FX + precious metals via Bank of Russia. Runs every 2 min (well within free
     * rate limits); fail-soft per source so a down API leaves the last good price.
     * This is what surfaces "биржевые цены" — MOEX equities stay synthetic (geo-blocked).
     */
    // NOT @Transactional: the three marketDataClient.fetch* calls below are
    // blocking external HTTP (6s connect + 12s read each). Holding a JPA tx —
    // and its Hikari connection — open across them would pin a connection for up
    // to ~54s during a third-party brownout and can exhaust the pool. Each
    // upsertFeed save auto-commits in its own short tx instead (the symbols here
    // are written by no other scheduler, so per-feed commits are safe).
    @Scheduled(fixedRate = 120_000, initialDelay = 8_000)
    public void syncRealMarketPrices() {
        int updated = 0;

        Map<String, MarketQuote> crypto = marketDataClient.fetchCryptoRub(new HashSet<>(CRYPTO_COINGECKO.values()));
        for (Map.Entry<String, String> e : CRYPTO_COINGECKO.entrySet()) {
            MarketQuote q = crypto.get(e.getValue());
            if (q != null) { upsertFeed(e.getKey(), q.priceRub(), q.change24hPct(), "COINGECKO"); updated++; }
        }

        Map<String, MarketQuote> fx = marketDataClient.fetchFxRub(new HashSet<>(FX_CBR.values()));
        for (Map.Entry<String, String> e : FX_CBR.entrySet()) {
            MarketQuote q = fx.get(e.getValue());
            if (q != null) { upsertFeed(e.getKey(), q.priceRub(), q.change24hPct(), "CBR-FX"); updated++; }
        }

        Map<Integer, MarketQuote> metals = marketDataClient.fetchMetalsRub();
        for (Map.Entry<String, Integer> e : METALS_CBR.entrySet()) {
            MarketQuote q = metals.get(e.getValue());
            if (q != null) { upsertFeed(e.getKey(), q.priceRub(), q.change24hPct(), "CBR-METALS"); updated++; }
        }

        log.info("Real market prices synced: {} feeds (crypto/FX/metals)", updated);
    }

    /**
     * Upserts a price feed by symbol and appends a {@code price_history} tick.
     *
     * <p>WHAT: finds the existing feed (or builds a new one), overwrites its
     * current price / source / 24h-change / timestamps, saves it, and records
     * one history row so the TWAP recalculation has a sample.
     *
     * <p>WHY a guard on price: a missing or non-positive price from a flaky
     * upstream is dropped silently so a bad fetch can't overwrite a good feed
     * with garbage (the last good price simply remains).
     *
     * @param symbol    platform asset symbol to upsert (already in final form,
     *                  e.g. {@code SBTC}, {@code SUSDT})
     * @param price     latest price in RUB; {@code null} or {@code <= 0} is a
     *                  no-op
     * @param change24h 24-hour percentage change; {@code null} is stored as
     *                  zero
     * @param source    provenance label written to the feed (e.g.
     *                  {@code COINGECKO}, {@code CBR-FX}, {@code CBR-METALS})
     */
    private void upsertFeed(String symbol, BigDecimal price, BigDecimal change24h, String source) {
        if (price == null || price.signum() <= 0) return;
        long nowMs = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        BigDecimal chg = change24h != null ? change24h : BigDecimal.ZERO;
        PriceFeed feed = priceFeedRepository.findByAssetSymbol(symbol)
                .orElseGet(() -> PriceFeed.builder()
                        .assetSymbol(symbol).source(source)
                        .currentPrice(price).twapPrice(price)
                        .priceChange24hPct(chg)
                        .updatedAtEpochMs(nowMs).updatedAt(now)
                        .build());
        feed.setCurrentPrice(price);
        feed.setSource(source);
        feed.setPriceChange24hPct(chg);
        feed.setUpdatedAtEpochMs(nowMs);
        feed.setUpdatedAt(now);
        priceFeedRepository.save(feed);
        priceHistoryRepository.save(PriceHistory.builder()
                .priceFeedId(feed.getId())
                .price(price)
                .timestampEpochMs(nowMs)
                .build());
    }

    /**
     * Synthetic random-walk for MOEX equities/indices that have no reachable
     * real feed.
     *
     * <p>WHAT: every 15 s nudges each symbol in {@link #INITIAL_PRICES} by a
     * ±1% step around its last value (floored just above zero), persists the
     * feed under source {@code SYNTHETIC}, and appends a history tick.
     *
     * <p>WHY synthetic: MOEX ISS is geo-blocked from this environment, so the
     * demo needs plausibly-moving equity/index prices. The {@code SYNTHETIC}
     * source label makes clear these are not real market quotes. Runs in its
     * own transaction so each cycle commits atomically.
     */
    @Scheduled(fixedRate = 15_000)
    @Transactional
    public void fetchPricesFromMoex() {
        log.debug("Fetching synthetic equity/index prices...");

        for (Map.Entry<String, BigDecimal> entry : INITIAL_PRICES.entrySet()) {
            String symbol = entry.getKey();
            BigDecimal basePrice = entry.getValue();

            BigDecimal currentPrice = lastPrices.getOrDefault(symbol, basePrice);
            BigDecimal change = currentPrice.multiply(
                    BigDecimal.valueOf((random.nextDouble() - 0.5) * 0.02), MC);
            BigDecimal newPrice = currentPrice.add(change).max(BigDecimal.valueOf(0.0001));

            lastPrices.put(symbol, newPrice);

            long nowMs = System.currentTimeMillis();
            LocalDateTime now = LocalDateTime.now();

            PriceFeed feed = priceFeedRepository.findByAssetSymbol(symbol)
                    .orElseGet(() -> PriceFeed.builder()
                            .assetSymbol(symbol)
                            .source("SYNTHETIC")
                            .currentPrice(newPrice)
                            .twapPrice(newPrice)
                            .priceChange24hPct(BigDecimal.ZERO)
                            .updatedAtEpochMs(nowMs)
                            .updatedAt(now)
                            .build());

            BigDecimal oldPrice = feed.getCurrentPrice();
            BigDecimal pctChange = BigDecimal.ZERO;
            if (oldPrice != null && oldPrice.compareTo(BigDecimal.ZERO) > 0) {
                pctChange = newPrice.subtract(oldPrice)
                        .divide(oldPrice, MC)
                        .multiply(BigDecimal.valueOf(100));
            }

            feed.setCurrentPrice(newPrice);
            feed.setPriceChange24hPct(pctChange);
            feed.setUpdatedAtEpochMs(nowMs);
            feed.setUpdatedAt(now);
            priceFeedRepository.save(feed);

            PriceHistory history = PriceHistory.builder()
                    .priceFeedId(feed.getId())
                    .price(newPrice)
                    .timestampEpochMs(nowMs)
                    .build();
            priceHistoryRepository.save(history);
        }

        log.debug("Mock MOEX prices updated for {} symbols", INITIAL_PRICES.size());
    }

    /**
     * Recomputes and stores the persisted {@code twapPrice} for every feed.
     *
     * <p>WHAT: every 60 s, for each feed averages its {@code price_history}
     * ticks inside the trailing {@link #TWAP_DEFAULT_PERIOD_MINUTES} window and
     * writes the mean back onto the feed; feeds with no ticks in the window are
     * left untouched (their last good TWAP stands).
     *
     * <p>WHY precompute: storing the TWAP on the feed lets the common
     * {@code GET /prices} listing expose it without an N+1 history scan per
     * request; {@link #getTwapPrice} still computes on demand for arbitrary
     * windows.
     */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void recalculateTwap() {
        log.debug("Recalculating TWAP for all price feeds...");

        List<PriceFeed> feeds = priceFeedRepository.findAll();
        long fromTimestamp = System.currentTimeMillis() - TWAP_DEFAULT_PERIOD_MINUTES * 60 * 1000L;

        for (PriceFeed feed : feeds) {
            List<PriceHistory> histories = priceHistoryRepository
                    .findByPriceFeedIdAndTimestampEpochMsGreaterThanEqual(feed.getId(), fromTimestamp);

            if (!histories.isEmpty()) {
                BigDecimal sum = BigDecimal.ZERO;
                for (PriceHistory history : histories) {
                    sum = sum.add(history.getPrice());
                }
                BigDecimal twap = sum.divide(BigDecimal.valueOf(histories.size()), MC);
                feed.setTwapPrice(twap);
                priceFeedRepository.save(feed);
            }
        }

        log.debug("TWAP recalculated for {} feeds", feeds.size());
    }

    /**
     * Throws if a feed has not been refreshed within the staleness threshold.
     *
     * <p>WHY: this is the single chokepoint that turns "the feed silently
     * stopped updating" into an explicit 503 for spot/TWAP reads, so no caller
     * can act on a frozen price. The message includes the actual age and the
     * threshold to make on-call diagnosis quick.
     *
     * @param feed the feed to age-check
     * @throws OracleUnavailableException if the feed's last update is older
     *                                    than {@link #STALENESS_THRESHOLD_MS}
     */
    private void checkStaleness(PriceFeed feed) {
        long ageMs = System.currentTimeMillis() - feed.getUpdatedAtEpochMs();
        if (ageMs > STALENESS_THRESHOLD_MS) {
            throw new OracleUnavailableException(
                    "Price data for " + feed.getAssetSymbol() + " is stale (last updated "
                            + (ageMs / 1000) + "s ago, threshold is " + (STALENESS_THRESHOLD_MS / 1000) + "s)");
        }
    }

    /**
     * Maps a {@link PriceFeed} entity to its API DTO.
     *
     * <p>WHY a dedicated mapper: keeps the JPA entity out of the controller's
     * serialization path and gives one place to control which fields are
     * exposed.
     *
     * @param feed the persisted feed
     * @return the corresponding {@link PriceFeedResponse}
     */
    private PriceFeedResponse toResponse(PriceFeed feed) {
        return new PriceFeedResponse(
                feed.getId(),
                feed.getAssetSymbol(),
                feed.getSource(),
                feed.getCurrentPrice(),
                feed.getTwapPrice(),
                feed.getPriceChange24hPct(),
                feed.getUpdatedAtEpochMs(),
                feed.getUpdatedAt()
        );
    }
}
