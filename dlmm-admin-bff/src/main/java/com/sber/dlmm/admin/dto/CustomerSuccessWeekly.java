package com.sber.dlmm.admin.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * B-04 (Batch #5, Sprint 14) — weekly customer-success snapshot for PO.
 *
 * <p>Aggregated metrics that the PO currently pulls manually from the
 * admin UI каждую неделю. Auto-generated и dropped в the response;
 * the email/Slack delivery hook is a separate cron job (out of scope
 * for this MVP — endpoint только).
 *
 * <p>Период: last 7 calendar days от sysdate.
 */
public record CustomerSuccessWeekly(
        LocalDate weekEnding,
        int activeOrgs,                  // orgs с ≥ 1 user login за last 7d
        int activeUsers,                 // users с ≥ 1 login за last 7d
        int newUsers,                    // users created за last 7d
        int totalTransactions,           // txs за last 7d
        int swapCount,                   // SWAP txs за last 7d
        double totalFeeRevenueRub,       // sum of feeAmount * estimated RUB rate
        double twoFaAdoptionPct,         // % of users с 2FA enabled
        double kycVerifiedPct,           // % of users с kyc=VERIFIED
        List<TopOrg> top5FeeYieldingOrgs,
        List<ChurnWarning> churnWarnings  // orgs с tx до 14d, но no recent
) {
    public record TopOrg(UUID orgId, String orgName, double feeRevenueRub, int activeUserCount) {}
    public record ChurnWarning(UUID orgId, String orgName, String lastTransactionDate, int daysSinceLast) {}
}
