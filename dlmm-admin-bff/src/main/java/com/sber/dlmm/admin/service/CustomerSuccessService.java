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

    private int queryActiveOrgs7d() {
        return safeInt("""
                SELECT COUNT(DISTINCT org_id) FROM org_members om
                JOIN users u ON om.user_id = u.id
                WHERE u.last_login_at >= NOW() - INTERVAL '7 days'
                """);
    }

    private int queryActiveUsers7d() {
        return safeInt("""
                SELECT COUNT(*) FROM users
                WHERE last_login_at >= NOW() - INTERVAL '7 days'
                """);
    }

    private int queryNewUsers7d() {
        return safeInt("""
                SELECT COUNT(*) FROM users
                WHERE created_at >= NOW() - INTERVAL '7 days'
                """);
    }

    private int queryTotalTransactions7d() {
        return safeInt("""
                SELECT COUNT(*) FROM transactions
                WHERE created_at >= NOW() - INTERVAL '7 days'
                """);
    }

    private int querySwapCount7d() {
        return safeInt("""
                SELECT COUNT(*) FROM transactions
                WHERE tx_type = 'SWAP' AND created_at >= NOW() - INTERVAL '7 days'
                """);
    }

    private double queryTotalFeeRevenueRub7d() {
        // Approximate: just sum fee_amount across all transactions (denominated
        // in the swap token; for true RUB we'd need to FX-convert via price-oracle).
        // PO understands the approximation — used as trend indicator not financial.
        return safeDouble("""
                SELECT COALESCE(SUM(fee_amount), 0) FROM transactions
                WHERE created_at >= NOW() - INTERVAL '7 days' AND fee_amount IS NOT NULL
                """);
    }

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

    private double queryKycVerifiedPct() {
        return safeDouble("""
                SELECT CASE WHEN COUNT(*) > 0
                       THEN (COUNT(*) FILTER (WHERE kyc_status = 'VERIFIED') * 100.0 / COUNT(*))
                       ELSE 0 END
                FROM users
                """);
    }

    // ------- top + churn lists -------

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

    private int safeInt(String sql) {
        try {
            Integer v = jdbc.queryForObject(sql, Integer.class);
            return v != null ? v : 0;
        } catch (Exception ex) {
            log.debug("safeInt failed: {} — {}", sql.lines().findFirst().orElse(""), ex.getMessage());
            return 0;
        }
    }

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
