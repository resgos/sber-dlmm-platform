package com.sber.dlmm.pool.dto;

import com.sber.dlmm.common.enums.PoolStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Summary view of a liquidity pool — the list/card DTO returned by
 * {@code GET /api/v1/pools} and embedded in {@link PoolDetailResponse}.
 *
 * <p>Carries the pool's identity, current market state and headline economics for
 * pool listings and dashboards.
 *
 * @param id                   pool id
 * @param tokenXId             id of the X (base) token
 * @param tokenYId             id of the Y (quote) token
 * @param tokenXSymbol         display symbol of the X token; best-effort, may be
 *                             {@code null} if token-service is unreachable
 * @param tokenYSymbol         display symbol of the Y token; best-effort, may be
 *                             {@code null} if token-service is unreachable
 * @param binStep              spacing between adjacent bins in basis points; sets the
 *                             price ratio between consecutive bins. NOT scaled
 * @param baseFeeBps           pool's base swap fee in basis points (the floor of the
 *                             dynamic fee); NOT scaled
 * @param activeBinId          id of the bin holding the current market price
 * @param currentPrice         current price (token_y per token_x ratio), a real
 *                             decimal — NOT scaled
 * @param totalTvlX            total X reserves across all bins, raw integer at 10⁻⁴ scale
 * @param totalTvlY            total Y reserves across all bins, raw integer at 10⁻⁴ scale
 * @param volume24h            trailing 24-hour swap volume, raw integer at 10⁻⁴ scale
 *                             (quote-token terms)
 * @param estimatedApy         estimated annualised yield for LPs, as a percent
 *                             (e.g. {@code 12.5} = 12.5%); NOT scaled
 * @param status               pool lifecycle status (e.g. ACTIVE / PAUSED)
 * @param createdAt            when the pool was created
 * @param totalFeesCollectedX  gross lifetime fees (LP + protocol) accrued on the X
 *                             side, raw integer at 10⁻⁴ scale
 * @param totalFeesCollectedY  gross lifetime fees (LP + protocol) accrued on the Y
 *                             side, raw integer at 10⁻⁴ scale
 */
public record PoolResponse(
        UUID id,
        UUID tokenXId,
        UUID tokenYId,
        String tokenXSymbol,
        String tokenYSymbol,
        int binStep,
        int baseFeeBps,
        int activeBinId,
        BigDecimal currentPrice,
        long totalTvlX,
        long totalTvlY,
        long volume24h,
        BigDecimal estimatedApy,
        PoolStatus status,
        LocalDateTime createdAt,
        // Gross lifetime fees (LP + protocol) accrued on each side. Exposed on
        // the list DTO so the admin-bff dashboard can aggregate platform-wide
        // "комиссия собрана" — previously only PoolDetailResponse carried these,
        // so the dashboard's sum read null → showed 0 ₽ (UI-test F-09).
        long totalFeesCollectedX,
        long totalFeesCollectedY
) {
}
