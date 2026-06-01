package com.sber.dlmm.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Analytics bundle for a single token, served by
 * {@code GET /api/v1/admin/tokens/{id}/analytics} and rendered on the admin
 * token-detail view.
 *
 * <p>Aggregated by {@code AdminService} from token-service and price-oracle; on a
 * downstream outage the figures degrade to zeros and the history to an empty
 * list. Supply figures are reported as raw on-chain values (deliberately left
 * unscaled, unlike tradeable quantities); the 24h transfer volume follows the
 * platform 4-decimal scale (1 unit = 10⁻⁴ token).
 *
 * @param totalSupply        total minted supply of the token, raw value (unscaled)
 * @param circulatingSupply  circulating supply of the token, raw value (unscaled)
 * @param holders            number of distinct addresses holding the token (count)
 * @param priceHistory       30-day price time-series for the token
 * @param transferVolume24h  trailing-24h transfer volume, raw integer (1 unit = 10⁻⁴ token)
 */
public record TokenAnalyticsResponse(
    long totalSupply,
    long circulatingSupply,
    int holders,
    List<PriceHistoryEntry> priceHistory,
    long transferVolume24h
) {

    /**
     * One sampled point on the token's price chart.
     *
     * @param timestamp sample time
     * @param price     token price at that time (quote-currency price; unscaled)
     */
    public record PriceHistoryEntry(
        LocalDateTime timestamp,
        BigDecimal price
    ) {}
}
