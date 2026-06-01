package com.sber.dlmm.admin.service;

import com.sber.dlmm.admin.dto.PilotHealth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * B-06 (Batch #5, Sprint 14) — per-org pilot health scoring.
 *
 * <p>Returns ranked list of all orgs с composite engagement score
 * (0-100), at-risk flag, и suggested actions. Used by PO в
 * /admin/pilots dashboard для at-a-glance churn watch.
 *
 * <h2>Score formula</h2>
 * <pre>
 *   score = 0
 *   + 30  if anyone in org logged in last 7d
 *   + 25  if org has ≥ 5 transactions in last 30d
 *   + 25  if org has ≥ 1 active position
 *   + 20  if org collected fees > 0 in last 30d
 *   ===
 *   ≤ 39 = AT_RISK (red)
 *   40-69 = WARNING (amber)
 *   ≥ 70 = HEALTHY (green)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PilotHealthService {

    private final JdbcTemplate jdbc;

    /**
     * Computes the pilot-health row for every org in one query, then derives the
     * composite score, traffic-light flag, and CSM action list per org in Java.
     *
     * <p>The SQL left-joins three pre-aggregated sub-selects (30-day tx count,
     * active-position count, 30-day fee sum) onto the org/member roster so a row
     * is produced even for orgs with no activity (counts come back 0 via
     * {@code COALESCE}). Scoring and suggestions are delegated to the package-
     * visible static helpers {@link #computeScore} / {@link #suggestActions}.
     *
     * @return one {@link PilotHealth} per org, ordered by org name; an empty
     *         list if the query fails (logged at WARN — never throws to caller)
     */
    public List<PilotHealth> getAllPilotHealth() {
        String sql = """
                SELECT
                    o.id AS org_id,
                    o.name AS org_name,
                    COUNT(DISTINCT om.user_id) AS member_count,
                    MAX(u.last_login_at) AS last_active,
                    COALESCE(tx_stats.tx_count, 0) AS tx_count_30d,
                    COALESCE(pos_stats.active_count, 0) AS active_positions,
                    COALESCE(fee_stats.fee_sum, 0) AS fees_30d
                FROM orgs o
                JOIN org_members om ON o.id = om.org_id
                JOIN users u ON om.user_id = u.id
                LEFT JOIN (
                    SELECT om2.org_id, COUNT(*) AS tx_count
                    FROM org_members om2
                    JOIN transactions t ON om2.user_id = t.user_id
                    WHERE t.created_at >= NOW() - INTERVAL '30 days'
                    GROUP BY om2.org_id
                ) tx_stats ON o.id = tx_stats.org_id
                LEFT JOIN (
                    SELECT om2.org_id, COUNT(*) AS active_count
                    FROM org_members om2
                    JOIN lp_positions p ON om2.user_id = p.user_id
                    WHERE p.is_active = TRUE
                    GROUP BY om2.org_id
                ) pos_stats ON o.id = pos_stats.org_id
                LEFT JOIN (
                    SELECT om2.org_id, COALESCE(SUM(t.fee_amount), 0) AS fee_sum
                    FROM org_members om2
                    JOIN transactions t ON om2.user_id = t.user_id
                    WHERE t.created_at >= NOW() - INTERVAL '30 days'
                      AND t.fee_amount IS NOT NULL
                    GROUP BY om2.org_id
                ) fee_stats ON o.id = fee_stats.org_id
                GROUP BY o.id, o.name, tx_stats.tx_count, pos_stats.active_count, fee_stats.fee_sum
                ORDER BY o.name
                """;

        try {
            return jdbc.query(sql, (rs, rn) -> {
                var orgId = rs.getObject("org_id", java.util.UUID.class);
                var orgName = rs.getString("org_name");
                var memberCount = rs.getInt("member_count");
                var lastActive = rs.getTimestamp("last_active") != null
                        ? rs.getTimestamp("last_active").toLocalDateTime() : null;
                var txCount = rs.getInt("tx_count_30d");
                var activePositions = rs.getInt("active_positions");
                var fees = rs.getDouble("fees_30d");

                int score = computeScore(lastActive, txCount, activePositions, fees);
                String flag = score < 40 ? "AT_RISK" : score < 70 ? "WARNING" : "HEALTHY";
                List<String> actions = suggestActions(lastActive, txCount, activePositions, fees);

                return new PilotHealth(
                        orgId, orgName, memberCount, lastActive,
                        txCount, activePositions, fees, score, flag, actions
                );
            });
        } catch (Exception ex) {
            log.warn("getAllPilotHealth failed: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Computes the 0–100 composite engagement score from the four signals, per
     * the additive formula in the class Javadoc (30 recent-login + 25 tx-volume
     * + 25 active-position + 20 fee-earning). Package-visible and static so it
     * can be unit-tested directly.
     *
     * @param lastActive      most recent member login, or {@code null} if never
     * @param txCount30d      transactions in the last 30 days
     * @param activePositions count of active LP positions
     * @param fees30d         fees collected in the last 30 days
     * @return the summed score in the range 0–100
     */
    static int computeScore(java.time.LocalDateTime lastActive,
                             int txCount30d, int activePositions, double fees30d) {
        int score = 0;
        if (lastActive != null
                && lastActive.isAfter(java.time.LocalDateTime.now().minusDays(7))) {
            score += 30;
        }
        if (txCount30d >= 5) score += 25;
        if (activePositions >= 1) score += 25;
        if (fees30d > 0) score += 20;
        return score;
    }

    /**
     * Derives the list of suggested CSM actions (in Russian, shown verbatim in
     * the dashboard) from the same four signals. Each unhealthy signal adds a
     * specific nudge; a fully-healthy org gets a single "keep going" line so the
     * list is never empty. Package-visible and static for direct unit testing.
     *
     * @param lastActive      most recent member login, or {@code null} if never
     * @param txCount30d      transactions in the last 30 days
     * @param activePositions count of active LP positions
     * @param fees30d         fees collected in the last 30 days
     * @return a non-empty, ordered list of suggested-action strings
     */
    static List<String> suggestActions(java.time.LocalDateTime lastActive,
                                        int txCount30d, int activePositions, double fees30d) {
        List<String> actions = new ArrayList<>();
        if (lastActive == null
                || lastActive.isBefore(java.time.LocalDateTime.now().minusDays(7))) {
            actions.add("Связаться: организация не логинилась более 7 дней");
        }
        if (txCount30d < 5) {
            actions.add("Onboarding-звонок: < 5 транзакций за 30 дней");
        }
        if (activePositions == 0) {
            actions.add("Помочь с первой позицией ликвидности");
        }
        if (fees30d == 0 && activePositions > 0) {
            actions.add("Проверить, что позиции in-range (out-of-range = 0 fees)");
        }
        if (actions.isEmpty()) actions.add("Здоровая организация — продолжать стандартный CSM cycle");
        return actions;
    }
}
