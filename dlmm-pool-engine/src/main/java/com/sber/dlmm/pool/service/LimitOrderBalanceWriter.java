package com.sber.dlmm.pool.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Credits a user's available balance with one atomic UPSERT on the shared
 * {@code user_balances} table.
 *
 * <p>Why direct DB and not {@code TokenServiceClient.creditBalance}: the limit-
 * order FILL runs on the scheduler thread, which has no inbound JWT for
 * {@code BearerTokenForwardingFilter} to forward — token-service would reject the
 * call 401/403 and orders would never settle. This mirrors {@link
 * PoolPriceSyncService} reading the shared DB directly for the same reason.
 *
 * <p>The bigger win: because this runs inside the caller's {@code @Transactional}
 * (same DataSource → same connection/tx as the JPA save), the credit commits or
 * rolls back together with the order's {@code @Version} status update. So an
 * optimistic-lock race (e.g. a cancel landing as the watcher fills) rolls the
 * loser's credit back too — no cross-service double-payout. CANCEL uses it for
 * the same atomicity; PLACE keeps the token-service deduct (it needs the
 * sufficient-balance check, and runs request-scoped so auth is fine).
 *
 * <p>The {@code user_balances} schema is just {@code (user_id, token_id,
 * available, locked, updated_at)} with a credit being {@code available +=
 * amount} (create the row if absent) — exactly what token-service's credit does,
 * so this stays faithful to that contract.
 */
@Component
public class LimitOrderBalanceWriter {

    private static final String CREDIT_SQL =
            "INSERT INTO user_balances (user_id, token_id, available, locked, updated_at) "
            + "VALUES (?, ?, ?, 0, CURRENT_TIMESTAMP) "
            + "ON CONFLICT (user_id, token_id) DO UPDATE "
            + "SET available = user_balances.available + EXCLUDED.available, "
            + "    updated_at = CURRENT_TIMESTAMP";

    private final JdbcTemplate jdbcTemplate;

    public LimitOrderBalanceWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Credit {@code amount} raw units of {@code tokenId} to {@code userId}; creates the row if absent. */
    public void credit(UUID userId, UUID tokenId, long amount) {
        if (amount <= 0) return;
        jdbcTemplate.update(CREDIT_SQL, userId, tokenId, amount);
    }
}
