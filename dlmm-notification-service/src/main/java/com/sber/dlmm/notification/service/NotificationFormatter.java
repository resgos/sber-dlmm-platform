package com.sber.dlmm.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Renders raw money values into human-readable notification copy (audit B2 / arch
 * P1-6 / Ouroboros High-3): the listeners previously emitted the raw integer amount
 * and the literal placeholders {@code "tokenX"} / {@code "tokenY"} (e.g.
 * {@code "9889190000 tokenX + 36058050000 tokenY"}). This turns that into
 * {@code "988 905,00 SBTC + 3 605 805,00 SRUB"}.
 *
 * <ul>
 *   <li><b>Amount scale:</b> every backend amount is a raw integer where 1 unit =
 *       10⁻⁴ token (the uniform ×10⁴ platform scale — see CLAUDE.md / scale.ts), so
 *       human = raw / 10000. Formatted with RU grouping and up to 4 decimals,
 *       trailing zeros trimmed.</li>
 *   <li><b>Symbols:</b> resolved from the shared {@code tokens} / {@code liquidity_pools}
 *       tables by id; a lookup miss degrades to a neutral fallback rather than failing
 *       the notification.</li>
 * </ul>
 *
 * <p>{@link DecimalFormat} is not thread-safe, so a fresh instance is built per call
 * (the notification path is not hot).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationFormatter {

    /** 1 raw unit = 10⁻⁴ token (uniform platform amount scale). */
    private static final long AMOUNT_SCALE = 10_000L;

    private final JdbcTemplate jdbcTemplate;

    /** Raw integer (×10⁴) → human amount string, RU grouping, ≤4 decimals, zeros trimmed. */
    public String amount(long raw) {
        BigDecimal human = BigDecimal.valueOf(raw).movePointLeft(4); // / 10000
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.forLanguageTag("ru"));
        DecimalFormat df = new DecimalFormat("#,##0.####", sym);
        return df.format(human);
    }

    /** Convenience: {@code "<amount> <SYMBOL>"} for one token leg. */
    public String amountWithSymbol(long raw, String tokenId) {
        return amount(raw) + " " + tokenSymbol(tokenId);
    }

    /** Token symbol by id; falls back to {@code "токен"} when unknown/unreadable. */
    public String tokenSymbol(String tokenId) {
        if (tokenId == null || tokenId.isBlank() || "null".equals(tokenId)) {
            return "токен";
        }
        try {
            String s = jdbcTemplate.queryForObject(
                    "SELECT symbol FROM tokens WHERE id = ?::uuid", String.class, tokenId);
            return s != null ? s : "токен";
        } catch (RuntimeException e) {
            log.debug("token symbol lookup failed for {}: {}", tokenId, e.getMessage());
            return "токен";
        }
    }

    /** Pool's {@code [tokenXSymbol, tokenYSymbol]}; falls back to {@code [X, Y]}. */
    public String[] poolTokenSymbols(String poolId) {
        try {
            String[] r = jdbcTemplate.queryForObject(
                    "SELECT tx.symbol, ty.symbol FROM liquidity_pools lp " +
                    "JOIN tokens tx ON tx.id = lp.token_x_id JOIN tokens ty ON ty.id = lp.token_y_id " +
                    "WHERE lp.id = ?::uuid",
                    (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, poolId);
            return r != null ? r : new String[]{"X", "Y"};
        } catch (RuntimeException e) {
            log.debug("pool token lookup failed for {}: {}", poolId, e.getMessage());
            return new String[]{"X", "Y"};
        }
    }

    /** Human-readable pool label {@code "TX/TY"} (symbols, not the UUID). */
    public String poolLabel(String poolId) {
        String[] t = poolTokenSymbols(poolId);
        return t[0] + "/" + t[1];
    }
}
