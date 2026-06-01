package com.sber.dlmm.oracle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Latest-known price feed row for a single asset.
 *
 * <p>Unlike {@link PriceHistory} (an append-only time series of samples),
 * this table holds exactly one mutable "current state" row per asset symbol
 * (enforced by the unique constraint on {@code assetSymbol}). It is updated
 * in place each time the oracle refreshes a quote, and is what most read
 * endpoints serve as the live spot price.
 *
 * <p>Monetary fields are expressed in rubles (SRUB) per unit of the asset.
 * {@code priceChange24hPct} is a percentage (e.g. {@code 1.50} = +1.5%),
 * not a fraction.
 */
@Entity
@Table(name = "price_feeds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceFeed {

    /** Surrogate primary key (server-generated UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Asset ticker this feed tracks (e.g. {@code "USD"}, {@code "SBTC"}); unique per row. */
    @Column(unique = true)
    private String assetSymbol;

    /** Origin of the quote (e.g. external market-data provider or {@code "CBR"}). */
    private String source;

    /** Latest spot price, in rubles (SRUB) per unit of the asset. */
    private BigDecimal currentPrice;

    /** Time-weighted average price over the oracle's TWAP window, in rubles per unit. */
    private BigDecimal twapPrice;

    /** Trailing-24h price change as a signed percentage (e.g. {@code -2.30} = -2.3%). */
    // DB column is `price_change_24h_pct` (init-db.sql). Hibernate's
    // naming convention would translate `priceChange24hPct` to
    // `price_change24h_pct` (no underscore before 24) and fail
    // schema-validation. Explicit @Column avoids the rename.
    @Column(name = "price_change_24h_pct")
    private BigDecimal priceChange24hPct;

    /** Last-refresh instant as Unix epoch milliseconds (machine-friendly, timezone-free). */
    private long updatedAtEpochMs;

    /** Last-refresh instant as a {@link LocalDateTime} (human/serialisation-friendly mirror of {@code updatedAtEpochMs}). */
    private LocalDateTime updatedAt;
}
