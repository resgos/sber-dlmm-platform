package com.sber.dlmm.admin.service;

import com.sber.dlmm.admin.dto.CustomerSuccessWeekly;
import com.sber.dlmm.admin.dto.CustomerSuccessWeekly.ChurnWarning;
import com.sber.dlmm.admin.dto.CustomerSuccessWeekly.TopOrg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * B-04 (Batch #5, Sprint 14) — weekly customer-success aggregates.
 *
 * <p>Generates the snapshot that the PO currently pulls manually
 * каждый понедельник. Single endpoint, single round-trip, single
 * DTO — keeps the report cohesive.
 *
 * <p>Все queries read-only, не блокируют другие модули. Robust
 * to missing tables / empty schemas (returns zeros, не throws).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerSuccessService {

    private final JdbcTemplate jdbc;

    /**
     * Assembles the full weekly customer-success snapshot in a single
     * round-trip's worth of read-only queries. Each contributing query is
     * independently failure-tolerant (see {@link #safeInt}/{@link #safeDouble}
     * and the per-list try/catch), so a missing table or empty schema yields
     * zeros / empty lists rather than failing the whole report.
     *
     * @return a {@link CustomerSuccessWeekly} dated to today, with all activity,
     *         revenue, adoption, top-org and churn fields populated
     */
    public CustomerSuccessWeekly getWeeklySnapshot() {
        LocalDate weekEnding = LocalDate.now();

        return new CustomerSuccessWeekly(
                weekEnding,
                queryActiveOrgs7d(),
                queryActiveUsers7d(),
                queryNewUsers7d(),
                queryTotalTransactions7d(),
                querySwapCount7d(),
                queryTotalFeeRevenueRub7d(),
                queryTwoFaAdoptionPct(),
                queryKycVerifiedPct(),
                queryTop5FeeYieldingOrgs(),
                queryChurnWarnings()
        );
    }

    // ------- active counts -------

    /**
     * Counts orgs with at least one member who logged in within the last 7 days.
     *
     * @return distinct active-org count, or 0 on query failure
     */
    private int queryActiveOrgs7d() {
        return safeInt("""
                SELECT COUNT(DISTINCT org_id) FROM org_members om
                JOIN users u ON om.user_id = u.id
                WHERE u.last_login_at >= NOW() - INTERVAL '7 days'
                """);
    }

    /**
     * Counts users who logged in within the last 7 days.
     *
     * @return active-user count, or 0 on query failure
     */
    private int queryActiveUsers7d() {
        return safeInt("""
                SELECT COUNT(*) FROM users
                WHERE last_login_at >= NOW() - INTERVAL '7 days'
                """);
    }

    /**
     * Counts users created within the last 7 days.
     *
     * @return new-user count, or 0 on query failure
     */
    private int queryNewUsers7d() {
        return safeInt("""
                SELECT COUNT(*) FROM users
                WHERE created_at >= NOW() - INTERVAL '7 days'
                """);
    }

    /**
     * Counts all transactions created within the last 7 days.
     *
     * @return transaction count, or 0 on query failure
     */
    private int queryTotalTransactions7d() {
        return safeInt("""
                SELECT COUNT(*) FROM transactions
                WHERE created_at >= NOW() - INTERVAL '7 days'
                """);
    }

    /**
     * Counts SWAP-type transactions within the last 7 days.
     *
     * @return swap count, or 0 on query failure
     */
    private int querySwapCount7d() {
        return safeInt("""
                SELECT COUNT(*) FROM transactions
                WHERE tx_type = 'SWAP' AND created_at >= NOW() - INTERVAL '7 days'
                """);
    }

    /**
     * Sums fee revenue over the last 7 days.
     *
     * <p>Deliberately approximate: fee amounts are summed in their swap-token
     * units without FX-converting to RUB (true RUB would need the price-oracle).
     * Used as a trend indicator, not a financial figure — the PO accepts this.
     *
     * @return summed fee amount, or 0 on query failure
     */
    private double queryTotalFeeRevenueRub7d() {
        // Approximate: just sum fee_amount across all transactions (denominated
        // in the swap token; for true RUB we'd need to FX-convert via price-oracle).
        // PO understands the approximation — used as trend indicator not financial.
        return safeDouble("""
                SELECT COALESCE(SUM(fee_amount), 0) FROM transactions
                WHERE created_at >= NOW() - INTERVAL '7 days' AND fee_amount IS NOT NULL
                """);
    }

    /**
     * Computes the percentage of users with two-factor auth enabled.
     *
     * <p>Issued as two separate counts (enabled, total) rather than a single
     * filtered aggregate so a missing {@code two_factor_enabled} column degrades
     * cleanly to 0 instead of poisoning the whole snapshot.
     *
     * @return 2FA adoption as a 0–100 percentage, or 0 if the column/query fails
     *         or there are no users
     */
    private double queryTwoFaAdoptionPct() {
        // 2FA stored в users.two_factor_enabled (or similar — actual column
        // varies; falls back to 0 on missing column).
        try {
            int enabled = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE two_factor_enabled = TRUE",
                    Integer.class);
            int total = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
            return total > 0 ? (enabled * 100.0 / total) : 0.0;
        } catch (Exception ex) {
            log.debug("2FA adoption query failed (column missing?): {}", ex.getMessage());
            return 0.0;
        }
    }

    /**
     * Computes the percentage of users whose KYC status is {@code VERIFIED}.
     *
     * @return KYC-verified share as a 0–100 percentage, or 0 if there are no
     *         users or the query fails
     */
    private double queryKycVerifiedPct() {
        return safeDouble("""
                SELECT CASE WHEN COUNT(*) > 0
                       THEN (COUNT(*) FILTER (WHERE kyc_status = 'VERIFIED') * 100.0 / COUNT(*))
                       ELSE 0 END
                FROM users
                """);
    }

    // ------- top + churn lists -------

    /**
     * Returns the top 5 orgs by fee revenue over the last 7 days, each with its
     * summed fees and distinct active-user count.
     *
     * @return up to 5 {@link TopOrg} rows ordered by fee sum descending, or an
     *         empty list on query failure
     */
    private List<TopOrg> queryTop5FeeYieldingOrgs() {
        try {
            return jdbc.query("""
                    SELECT o.id, o.name, COALESCE(SUM(t.fee_amount), 0) as fee_sum,
                           COUNT(DISTINCT t.user_id) as user_count
                    FROM orgs o
                    JOIN org_members om ON o.id = om.org_id
                    LEFT JOIN transactions t ON om.user_id = t.user_id
                        AND t.created_at >= NOW() - INTERVAL '7 days'
                        AND t.fee_amount IS NOT NULL
                    GROUP BY o.id, o.name
                    ORDER BY fee_sum DESC
                    LIMIT 5
                    """, (rs, rn) -> new TopOrg(
                    rs.getObject("id", java.util.UUID.class),
                    rs.getString("name"),
                    rs.getDouble("fee_sum"),
                    rs.getInt("user_count")
            ));
        } catch (Exception ex) {
            log.debug("top5FeeYieldingOrgs failed: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Finds orgs that are "cooling down": their most recent transaction is older
     * than 7 days but still within 30 days, so they remain in the active dataset
     * yet warrant a churn-prevention nudge.
     *
     * @return up to 10 {@link ChurnWarning} rows (org, last-tx date, days since),
     *         ordered by days-since descending, or empty on query failure
     */
    private List<ChurnWarning> queryChurnWarnings() {
        // Orgs whose latest transaction is > 7d ago but < 30d ago
        // (so they're still в active dataset but cooling down).
        try {
            return jdbc.query("""
                    SELECT o.id, o.name,
                           MAX(t.created_at) as last_tx,
                           EXTRACT(DAY FROM NOW() - MAX(t.created_at))::int as days_since
                    FROM orgs o
                    JOIN org_members om ON o.id = om.org_id
                    JOIN transactions t ON om.user_id = t.user_id
                    GROUP BY o.id, o.name
                    HAVING MAX(t.created_at) < NOW() - INTERVAL '7 days'
                       AND MAX(t.created_at) >= NOW() - INTERVAL '30 days'
                    ORDER BY days_since DESC
                    LIMIT 10
                    """, (rs, rn) -> new ChurnWarning(
                    rs.getObject("id", java.util.UUID.class),
                    rs.getString("name"),
                    rs.getTimestamp("last_tx").toString().substring(0, 10),
                    rs.getInt("days_since")
            ));
        } catch (Exception ex) {
            log.debug("churnWarnings failed: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    // ------- safe primitives -------

    /**
     * Runs a single-value {@code int} query, swallowing any exception (e.g. a
     * missing table) to keep the snapshot resilient.
     *
     * @param sql a query selecting exactly one integer column
     * @return the value, or 0 on null result or any failure
     */
    private int safeInt(String sql) {
        try {
            Integer v = jdbc.queryForObject(sql, Integer.class);
            return v != null ? v : 0;
        } catch (Exception ex) {
            log.debug("safeInt failed: {} — {}", sql.lines().findFirst().orElse(""), ex.getMessage());
            return 0;
        }
    }

    /**
     * Runs a single-value {@code double} query, swallowing any exception to keep
     * the snapshot resilient.
     *
     * @param sql a query selecting exactly one numeric column
     * @return the value, or 0.0 on null result or any failure
     */
    private double safeDouble(String sql) {
        try {
            Double v = jdbc.queryForObject(sql, Double.class);
            return v != null ? v : 0.0;
        } catch (Exception ex) {
            log.debug("safeDouble failed: {} — {}", sql.lines().findFirst().orElse(""), ex.getMessage());
            return 0.0;
        }
    }
}
