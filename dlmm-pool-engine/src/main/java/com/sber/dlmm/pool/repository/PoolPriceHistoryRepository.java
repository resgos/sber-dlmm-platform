package com.sber.dlmm.pool.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * G-23 (Batch #3) — read-only access to the shared {@code price_history}
 * table for per-pool risk-metric computation.
 *
 * <h2>Why not a {@code @Entity}?</h2>
 *
 * <p>{@code price_history} is owned by {@code dlmm-price-oracle} (see
 * {@code com.sber.dlmm.oracle.entity.PriceHistory}). Duplicating it as a
 * second JPA entity in pool-engine would create a Liquibase ownership
 * footgun (which service "owns" the changeset?) and a multi-entity-name
 * conflict if the two services ever shared a classpath in tests.
 *
 * <p>Native query against the same shared {@code dlmm} database is the
 * pragmatic equivalent of the read-replica pattern: we don't write the
 * table, we just read it.
 *
 * <h2>Pool → price-feed mapping</h2>
 *
 * <p>{@code price_history.price_feed_id} keys on the ASSET, not the
 * pool. Pools have two tokens. For the spec's "30-day realised
 * volatility" question we want the volatility of the pool's exchange
 * rate — which is dominated by the non-quote token because the quote
 * side (SRUB in the seeded catalogue) is intentionally stable.
 *
 * <p>We resolve pool → asset via:
 * <pre>
 *   liquidity_pools.token_x_id → tokens.price_oracle_id → price_feeds.source
 *   → price_feeds.id → price_history.price_feed_id
 * </pre>
 *
 * <p>If the pool's X-token lacks a feed we try Y-token as a fallback —
 * not all seeded pools have SRUB on the Y side, and a sample of zero
 * is worse than a sample of "the other side". When neither side has
 * history we return an empty list and the service degrades to
 * {@code isReliable=false}.
 *
 * <h2>Daily collapse</h2>
 *
 * <p>The seed feed publishes a row every ~15 seconds (we measured 5760
 * rows/day for SBER). Risk metrics need ONE point per day, not 5760.
 * The {@link #findDailyMeanPricesForPool(UUID, int)} call aggregates
 * via {@code AVG(price) GROUP BY day} at the SQL layer so the JVM
 * never sees the high-cardinality raw series.
 */
@Repository
public class PoolPriceHistoryRepository {

    private static final long ONE_DAY_MS = 86_400_000L;

    private final EntityManager em;

    public PoolPriceHistoryRepository(EntityManager em) {
        this.em = em;
    }

    /**
     * Daily mean prices for the pool's primary (X-side) token, falling
     * back to Y-side if X has no history. Returned in ASC date order
     * with the oldest day first — matches the spec's
     * "ORDER BY recorded_at ASC".
     *
     * <p>Empty list when:
     * <ul>
     *   <li>The pool doesn't exist.</li>
     *   <li>Neither token has a {@code price_oracle_id}.</li>
     *   <li>The feed exists but has no rows in the requested window.</li>
     * </ul>
     *
     * @param poolId     pool to look up
     * @param windowDays how far back from now to look (e.g. 30)
     * @return immutable list of (day → mean price) pairs, oldest first
     */
    public List<DailyPrice> findDailyMeanPricesForPool(UUID poolId, int windowDays) {
        if (poolId == null || windowDays <= 0) return Collections.emptyList();

        long now = System.currentTimeMillis();
        long since = now - windowDays * ONE_DAY_MS;

        List<DailyPrice> xSide = queryDailyMeans(poolId, "token_x_id", since);
        if (!xSide.isEmpty()) return xSide;
        // Fallback: X had no feed / no rows. Try the Y-side.
        return queryDailyMeans(poolId, "token_y_id", since);
    }

    @SuppressWarnings("unchecked")
    private List<DailyPrice> queryDailyMeans(UUID poolId, String tokenColumn, long sinceEpochMs) {
        // Parameterised on column name — only "token_x_id" / "token_y_id"
        // pass in, so no SQL-injection surface. The pool-id is a bound
        // parameter via setParameter, not string-interpolated.
        String sql = "SELECT FLOOR(ph.timestamp_epoch_ms / " + ONE_DAY_MS + ") AS day_bucket, " +
                "       AVG(ph.price) AS avg_price " +
                "FROM price_history ph " +
                "JOIN price_feeds pf ON pf.id = ph.price_feed_id " +
                "JOIN tokens t ON t.price_oracle_id = pf.source " +
                "JOIN liquidity_pools lp ON lp." + tokenColumn + " = t.id " +
                "WHERE lp.id = :poolId " +
                "  AND ph.timestamp_epoch_ms >= :since " +
                "GROUP BY day_bucket " +
                "ORDER BY day_bucket ASC";

        List<Object[]> rows;
        try {
            rows = em.createNativeQuery(sql)
                    .setParameter("poolId", poolId)
                    .setParameter("since", sinceEpochMs)
                    .getResultList();
        } catch (RuntimeException ex) {
            // Defensive — schema drift / connection blip must not break
            // the comparator UX. Caller falls back to isReliable=false.
            return Collections.emptyList();
        }

        if (rows == null || rows.isEmpty()) return Collections.emptyList();

        List<DailyPrice> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            long dayBucket = ((Number) row[0]).longValue();
            BigDecimal price = toBigDecimal(row[1]);
            if (price == null || price.signum() <= 0) continue;
            LocalDate day = LocalDate.ofEpochDay(dayBucket);
            out.add(new DailyPrice(day, price.doubleValue()));
        }
        return out;
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        return null;
    }

    /**
     * One (day, mean-price) tuple. {@code day} is the calendar date in
     * UTC computed from the epoch-ms bucket; {@code price} is the daily
     * mean over all raw rows for that day. {@code double} is fine for
     * statistical aggregation — see G-23 spec.
     */
    public record DailyPrice(LocalDate day, double price) {}

    /** Helper for unit tests that want to construct synthetic series. */
    public static long epochMsOfDay(LocalDate day) {
        return day.atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000L;
    }
}
