package com.sber.dlmm.admin.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * B-06 (Batch #5, Sprint 14) — pilot health dashboard row.
 *
 * <p>Per-org composite engagement score + at-risk flag. PO opens
 * /admin/pilots → видит таблицу всех pilot orgs sorted by score asc
 * (worst at top, immediate action items first).
 *
 * <h2>Engagement score (0-100)</h2>
 * <p>Composite of:
 * <ul>
 *   <li>+30 if logged in within last 7d</li>
 *   <li>+25 если ≥ 5 transactions за last 30d</li>
 *   <li>+25 если ≥ 1 active position</li>
 *   <li>+20 если total fees collected за last 30d &gt; 0</li>
 * </ul>
 * Score &lt; 40 = at-risk (red flag), 40-69 = warning, 70+ = healthy.
 *
 * <p>Multi-member orgs counted с aggregated transactions / positions
 * (org-level not user-level), но last-login = max over members
 * (any member active = org active).
 *
 * @param orgId            identifier of the pilot organisation
 * @param orgName          display name of the organisation
 * @param memberCount      number of users in the organisation (count)
 * @param lastActiveAt     most recent login across all members (max over members)
 * @param transactions30d  transactions by the org in the last 30 days (count)
 * @param activePositions  open liquidity positions held by the org (count)
 * @param fees30dRub        fees collected by the org in the last 30 days, in roubles (₽)
 * @param engagementScore  composite engagement score in the range 0–100
 * @param healthFlag       derived band: {@code HEALTHY}, {@code WARNING} or {@code AT_RISK}
 * @param suggestedActions human-readable next-step recommendations for the org
 */
public record PilotHealth(
        UUID orgId,
        String orgName,
        int memberCount,
        LocalDateTime lastActiveAt,
        int transactions30d,
        int activePositions,
        double fees30dRub,
        int engagementScore,        // 0-100
        String healthFlag,          // HEALTHY | WARNING | AT_RISK
        List<String> suggestedActions  // human-readable next-steps
) {}
