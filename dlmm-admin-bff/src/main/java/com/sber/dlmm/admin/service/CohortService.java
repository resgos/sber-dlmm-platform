package com.sber.dlmm.admin.service;

import com.sber.dlmm.admin.dto.CohortDataPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Cohort analytics service — queries the shared {@code dlmm} Postgres database
 * directly via {@link JdbcTemplate}.
 *
 * <p>Supported metrics:
 * <ul>
 *   <li><b>DAU</b> — distinct users with ≥ 1 transaction on each calendar day.</li>
 *   <li><b>MAU</b> — distinct users active in the trailing 30-day window ending
 *       on each calendar day.</li>
 *   <li><b>D7</b> — percentage (0–100) of users first seen in week N who also
 *       transacted in week N+1.</li>
 *   <li><b>D30</b> — percentage (0–100) of users first seen in month M who also
 *       transacted in month M+1.</li>
 * </ul>
 * All queries are read-only and scoped to the trailing {@code days} calendar days.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CohortService {

    private final JdbcTemplate jdbc;

    /**
     * Returns a time-series of the requested metric.
     *
     * @param metric one of DAU, MAU, D7, D30 (case-insensitive)
     * @param days   number of trailing calendar days to include
     * @return list of data points ordered by date ascending
     */
    public List<CohortDataPoint> getCohortData(String metric, int days) {
        int safeDays = Math.max(1, Math.min(days, 365));
        log.debug("getCohortData: metric={}, days={}", metric, safeDays);

        return switch (metric.toUpperCase()) {
            case "DAU"  -> queryDau(safeDays);
            case "MAU"  -> queryMau(safeDays);
            case "D7"   -> queryRetention(safeDays, 7);
            case "D30"  -> queryRetention(safeDays, 30);
            default     -> queryDau(safeDays);
        };
    }

    // ── DAU ──────────────────────────────────────────────────────────────────────

    /**
     * Distinct users with at least one transaction on each calendar day.
     *
     * <pre>
     * SELECT DATE(created_at) AS day,
     *        COUNT(DISTINCT user_id) AS value
     * FROM   transactions
     * WHERE  created_at >= NOW() - INTERVAL '<days> days'
     * GROUP  BY DATE(created_at)
     * ORDER  BY day
     * </pre>
     */
    private List<CohortDataPoint> queryDau(int days) {
        String sql = """
                SELECT DATE(created_at)          AS day,
                       COUNT(DISTINCT user_id)   AS value
                FROM   transactions
                WHERE  created_at >= NOW() - (?::int * INTERVAL '1 day')
                GROUP  BY DATE(created_at)
                ORDER  BY day
                """;
        return jdbc.query(sql, (rs, rowNum) ->
                new CohortDataPoint(rs.getString("day"), rs.getLong("value")),
                days);
    }

    // ── MAU ──────────────────────────────────────────────────────────────────────

    /**
     * Distinct users active in the trailing 30-day window ending on each day.
     *
     * <p>For each calendar day {@code d} in the range we count how many distinct
     * users had at least one transaction in {@code [d − 29 days, d]}.
     */
    private List<CohortDataPoint> queryMau(int days) {
        String sql = """
                WITH day_series AS (
                    SELECT generate_series(
                               DATE(NOW()) - (?::int - 1) * INTERVAL '1 day',
                               DATE(NOW()),
                               INTERVAL '1 day'
                           )::date AS day
                )
                SELECT ds.day::text                              AS day,
                       COUNT(DISTINCT t.user_id)                AS value
                FROM   day_series ds
                LEFT JOIN transactions t
                       ON t.created_at >= ds.day - 29
                      AND t.created_at <  ds.day + INTERVAL '1 day'
                GROUP  BY ds.day
                ORDER  BY ds.day
                """;
        return jdbc.query(sql, (rs, rowNum) ->
                new CohortDataPoint(rs.getString("day"), rs.getLong("value")),
                days);
    }

    // ── Retention (D7 / D30) ─────────────────────────────────────────────────────

    /**
     * Rolling retention rate: percentage of users first seen in a given period
     * who returned in the immediately following period of the same length.
     *
     * <p>The result is expressed as an integer percentage (0–100).
     * Days on which there are no new cohort members produce {@code value = 0}.
     *
     * @param days       trailing window (number of calendar days to show)
     * @param periodDays cohort bucket size (7 for D7, 30 for D30)
     */
    private List<CohortDataPoint> queryRetention(int days, int periodDays) {
        String sql = """
                WITH cohort_start AS (
                    -- The user's VERY FIRST transaction ever — full history,
                    -- NOT bounded by the trailing window. Bounding it counted
                    -- long-time users as fresh cohort members on their first
                    -- in-window day, inflating cohort_size and skewing the
                    -- reported retention downward.
                    SELECT user_id,
                           DATE(MIN(created_at)) AS first_day
                    FROM   transactions
                    GROUP  BY user_id
                ),
                period_series AS (
                    SELECT generate_series(
                               DATE(NOW()) - (?::int - 1) * INTERVAL '1 day',
                               DATE(NOW()),
                               INTERVAL '1 day'
                           )::date AS day
                ),
                cohort_counts AS (
                    SELECT ps.day,
                           COUNT(DISTINCT cs.user_id)  AS cohort_size,
                           COUNT(DISTINCT t2.user_id)  AS returned
                    FROM   period_series ps
                    LEFT JOIN cohort_start cs
                           ON cs.first_day >= ps.day - (?::int - 1)
                          AND cs.first_day <= ps.day
                    LEFT JOIN transactions t2
                           ON t2.user_id = cs.user_id
                          AND t2.created_at >= ps.day + INTERVAL '1 day'
                          AND t2.created_at <  ps.day + (?::int * INTERVAL '1 day') + INTERVAL '1 day'
                    GROUP  BY ps.day
                )
                SELECT day::text                                           AS day,
                       CASE WHEN cohort_size > 0
                            THEN ROUND(returned * 100.0 / cohort_size)
                            ELSE 0
                       END                                                 AS value
                FROM   cohort_counts
                ORDER  BY day
                """;
        return jdbc.query(sql, (rs, rowNum) ->
                new CohortDataPoint(rs.getString("day"), rs.getLong("value")),
                days, periodDays, periodDays);
    }
}
