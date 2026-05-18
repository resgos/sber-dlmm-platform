package com.sber.dlmm.oracle.service;

import com.sber.dlmm.oracle.client.CbrRatesClient;
import com.sber.dlmm.oracle.entity.PriceFeed;
import com.sber.dlmm.oracle.repository.PriceFeedRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sprint 5 #5.9 — pulls daily official CBR rates and persists them as
 * {@code PriceFeed} rows with source="CBR-OFFICIAL". Coexists with the
 * MOEX mock feed (source="MOEX") so callers can compare market vs
 * official ("spread vs CBR" tile on admin dashboard).
 *
 * <p>Scheduling: daily 14:00 MSK (after CBR's ~12:00 MSK publication
 * window). One-time fetch on application ready so a freshly-booted
 * environment doesn't wait until the next 14:00 to have CBR rates.
 *
 * <p>Symbols stored: "CBR-USD", "CBR-EUR", "CBR-CNY" by default (config
 * via {@code dlmm.cbr.currencies}). Anything else CBR publishes is ignored.
 *
 * <p>Disable in dev/test via {@code dlmm.cbr.enabled=false}.
 */
@Service
@ConditionalOnProperty(prefix = "dlmm.cbr", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CbrRatesService {

    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);
    private static final String CBR_SOURCE = "CBR-OFFICIAL";
    private static final String CBR_SYMBOL_PREFIX = "CBR-";

    private final CbrRatesClient cbrClient;
    private final PriceFeedRepository priceFeedRepository;

    @Value("${dlmm.cbr.currencies:USD,EUR,CNY}")
    private String currenciesCsv;

    /**
     * Cron-style schedule — daily at 14:00 server time. CBR posts rates
     * for the next working day around 12:00 MSK; 14:00 gives a 2h buffer
     * and lets the price-oracle Pod restart cleanly during the morning
     * window without missing a fetch.
     */
    @Scheduled(cron = "${dlmm.cbr.cron:0 0 14 * * *}")
    public void scheduledFetch() {
        fetchAndPersist("scheduled");
    }

    /**
     * Eager fetch on startup so a cold-boot environment has rates
     * immediately (otherwise admin dashboard shows "no CBR feed" until
     * next 14:00). Failures here are logged but don't kill boot —
     * scheduler will retry within 24h.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            fetchAndPersist("startup");
        } catch (RuntimeException ex) {
            log.warn("CBR startup fetch failed (will retry on 14:00 schedule): {}", ex.toString());
        }
    }

    @Transactional
    public int fetchAndPersist(String trigger) {
        Set<String> wanted = parseCurrencies();
        log.info("CBR fetch trigger={} wantedCurrencies={}", trigger, wanted);

        Map<String, BigDecimal> rates = cbrClient.fetchTodayRates();
        long nowMs = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        int persisted = 0;

        for (String code : wanted) {
            BigDecimal rate = rates.get(code);
            if (rate == null) {
                log.warn("CBR did not return rate for currency={} (response keys: {})",
                        code, rates.keySet());
                continue;
            }
            String symbol = CBR_SYMBOL_PREFIX + code;
            PriceFeed feed = priceFeedRepository.findByAssetSymbol(symbol)
                    .orElseGet(() -> PriceFeed.builder()
                            .assetSymbol(symbol)
                            .source(CBR_SOURCE)
                            .priceChange24hPct(BigDecimal.ZERO)
                            .build());
            BigDecimal previous = feed.getCurrentPrice();
            BigDecimal pctChange = BigDecimal.ZERO;
            if (previous != null && previous.compareTo(BigDecimal.ZERO) > 0) {
                pctChange = rate.subtract(previous)
                        .divide(previous, MC)
                        .multiply(BigDecimal.valueOf(100));
            }
            feed.setSource(CBR_SOURCE);
            feed.setCurrentPrice(rate);
            // CBR doesn't publish intraday TWAP — official daily rate IS the TWAP equivalent.
            feed.setTwapPrice(rate);
            feed.setPriceChange24hPct(pctChange);
            feed.setUpdatedAtEpochMs(nowMs);
            feed.setUpdatedAt(now);
            priceFeedRepository.save(feed);
            persisted++;
            log.info("CBR rate persisted: {}={} RUB (Δ {}%)",
                    symbol, rate.setScale(4, RoundingMode.HALF_UP), pctChange.setScale(2, RoundingMode.HALF_UP));
        }
        return persisted;
    }

    private Set<String> parseCurrencies() {
        Set<String> set = new HashSet<>();
        for (String s : currenciesCsv.split(",")) {
            String trimmed = s.trim().toUpperCase();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set;
    }
}
