package com.sber.dlmm.token.dto;

import java.util.UUID;

/**
 * Response payload describing a single user's holding of one token.
 *
 * <p>Returned by the balance read endpoints ({@code GET /balances/me},
 * {@code GET /balances/me/{tokenId}}, {@code GET /balances/user/{userId}})
 * and also handed back from mint/burn to show the affected balance after
 * the operation. A user who holds none of the token is represented as a
 * zero balance rather than a 404.
 *
 * <p>All amounts are raw integer quantities on the platform's uniform
 * scale where 1 unit = 10⁻⁴ token (×10⁴ raw); the token's own
 * {@code decimals} column is not applied here.
 *
 * @param userId    owner of the balance
 * @param tokenId   token the balance is denominated in
 * @param symbol    ticker symbol of the token (e.g. {@code SRUB}), denormalised for display convenience
 * @param available spendable amount, raw ×10⁴ (total minus locked)
 * @param locked    amount reserved/held (e.g. by open orders or pending ops), raw ×10⁴
 * @param total     full holding, raw ×10⁴ ({@code available + locked})
 */
public record BalanceResponse(
        UUID userId,
        UUID tokenId,
        String symbol,
        long available,
        long locked,
        long total
) {
}
