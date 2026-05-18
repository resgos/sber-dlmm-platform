package com.sber.dlmm.oracle.service;

import com.sber.dlmm.common.exception.OracleUnavailableException;
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
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class PriceOracleService {

    private static final long STALENESS_THRESHOLD_MS = 5 * 60 * 1000L;
    private static final int TWAP_DEFAULT_PERIOD_MINUTES = 15;
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
            Map.entry("EUR", new BigDecimal("95.00"))
    );

    private final PriceFeedRepository priceFeedRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final Random random = new Random();
    private final ConcurrentHashMap<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public PriceFeedResponse getPrice(String assetSymbol) {
        PriceFeed feed = priceFeedRepository.findByAssetSymbol(assetSymbol.toUpperCase())
                .orElseThrow(() -> new OracleUnavailableException(
                        "Price feed not found for symbol: " + assetSymbol));

        checkStaleness(feed);
        return toResponse(feed);
    }

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
     */
    static BigDecimal computeSpreadBps(BigDecimal dlmmRate, BigDecimal cbrRate) {
        if (cbrRate == null || cbrRate.compareTo(BigDecimal.ZERO) <= 0) return null;
        return dlmmRate.subtract(cbrRate)
                .divide(cbrRate, MC)
                .multiply(BigDecimal.valueOf(10_000))
                .setScale(2, RoundingMode.HALF_UP);
    }

    @Scheduled(fixedRate = 15_000)
    @Transactional
    public void fetchPricesFromMoex() {
        log.debug("Fetching mock MOEX prices...");

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
                            .source("MOEX")
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

    private void checkStaleness(PriceFeed feed) {
        long ageMs = System.currentTimeMillis() - feed.getUpdatedAtEpochMs();
        if (ageMs > STALENESS_THRESHOLD_MS) {
            throw new OracleUnavailableException(
                    "Price data for " + feed.getAssetSymbol() + " is stale (last updated "
                            + (ageMs / 1000) + "s ago, threshold is " + (STALENESS_THRESHOLD_MS / 1000) + "s)");
        }
    }

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
