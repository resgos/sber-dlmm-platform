package com.sber.dlmm.oracle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the price-oracle service (port 8086).
 *
 * <p>Role: the platform's single source of truth for asset prices and pool
 * chart data. It runs three kinds of price work, all driven by schedulers
 * enabled here via {@link EnableScheduling}:
 * <ul>
 *   <li><b>Real external feeds</b> — crypto from CoinGecko, FX and precious
 *       metals from the Bank of Russia (CBR), plus the official daily CBR FX
 *       fixing used for the "spread vs official" tile.</li>
 *   <li><b>Synthetic feeds</b> — a labelled random walk for MOEX equities and
 *       indices, whose real ISS feed is geo-blocked from this environment.</li>
 *   <li><b>OHLCV candles</b> — per-minute open/high/low/close/volume buckets
 *       aggregated from the {@code pool-events} Kafka stream and served to the
 *       TradingView-style pool chart.</li>
 * </ul>
 *
 * <p>Invariants worth knowing service-wide: every feed carries a last-updated
 * timestamp and a price older than the staleness threshold is rejected with
 * HTTP 503 rather than served silently; OHLCV is bucketed per minute and a
 * still-forming current minute is never persisted; TWAP is the arithmetic mean
 * of recorded ticks over a trailing window.
 *
 * <p>{@code scanBasePackages} includes {@code com.sber.dlmm.common} so the
 * shared auto-configured infra (JWT auth filter, error handling, secret
 * validation, WebClient bearer forwarding) is picked up alongside this
 * service's own beans.
 */
@SpringBootApplication(scanBasePackages = {"com.sber.dlmm.oracle", "com.sber.dlmm.common"})
@EnableScheduling
public class DlmmPriceOracleApplication {

    /**
     * Boots the price-oracle Spring context and starts the embedded web
     * server and all schedulers.
     *
     * @param args standard JVM command-line arguments forwarded to Spring Boot
     *             (e.g. {@code --server.port}, {@code --spring.profiles.active})
     */
    public static void main(String[] args) {
        SpringApplication.run(DlmmPriceOracleApplication.class, args);
    }
}
