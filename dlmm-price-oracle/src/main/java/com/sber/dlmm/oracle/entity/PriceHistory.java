package com.sber.dlmm.oracle.entity;

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
import java.util.UUID;

/**
 * Append-only price sample for a single asset's feed.
 *
 * <p>Each row is one immutable point in a time series: the spot price of an
 * asset captured at a given instant. New rows are inserted on every oracle
 * refresh (rather than updating in place like {@link PriceFeed}), so this
 * table is the raw history that backs trailing-window calculations such as
 * TWAP and the 24h change shown on the live feed.
 *
 * <p>Rows are linked to their owning {@link PriceFeed} by {@code priceFeedId}
 * and are typically queried as a window keyed by {@code timestampEpochMs}.
 */
@Entity
@Table(name = "price_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceHistory {

    /** Surrogate primary key (server-generated UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Foreign key to the owning {@link PriceFeed#getId() price feed} this sample belongs to. */
    private UUID priceFeedId;

    /** Sampled spot price at {@code timestampEpochMs}, in rubles (SRUB) per unit of the asset. */
    private BigDecimal price;

    /** Instant the sample was taken, as Unix epoch milliseconds; the time-series sort/window key. */
    private long timestampEpochMs;
}
