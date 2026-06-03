package com.sber.dlmm.transaction.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves human labels (token symbols, pool pair) for the transaction read API
 * (audit B3 — the read DTO previously exposed only raw {@code tokenInId} /
 * {@code tokenOutId} / {@code poolId} UUIDs, so the UI had to join the token and
 * pool catalogues client-side just to label a row).
 *
 * <p>Reads the shared {@code tokens} / {@code liquidity_pools} tables by id (this
 * is a single Postgres DB shared across services — the same access pattern the
 * {@code LiquidityEventConsumer} already uses) and memoises each id so the
 * per-row {@code toResponse} mapping stays cheap across a paged history. Symbols
 * are effectively immutable, so a plain process-lifetime cache (no TTL) is fine;
 * a miss is cached as {@code ""} to avoid re-querying unknown ids. Best-effort:
 * any miss or query failure degrades to {@code null} rather than failing the read.
 */
@Component
public class TxLabelResolver {

    private final JdbcTemplate jdbcTemplate;
    private final Map<UUID, String> symbolCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> poolPairCache = new ConcurrentHashMap<>();

    public TxLabelResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Token symbol by id, or {@code null} when the id is null/unknown/unreadable. */
    public String tokenSymbol(UUID tokenId) {
        if (tokenId == null) return null;
        String v = symbolCache.computeIfAbsent(tokenId, id -> {
            try {
                String s = jdbcTemplate.queryForObject(
                        "SELECT symbol FROM tokens WHERE id = ?::uuid", String.class, id.toString());
                return s != null ? s : "";
            } catch (RuntimeException e) {
                return ""; // cache the miss so we don't re-query a bad/unknown id
            }
        });
        return v.isEmpty() ? null : v;
    }

    /** Pool pair label {@code "TX/TY"} by id, or {@code null} when unknown/unreadable. */
    public String poolPair(UUID poolId) {
        if (poolId == null) return null;
        String v = poolPairCache.computeIfAbsent(poolId, id -> {
            try {
                String s = jdbcTemplate.queryForObject(
                        "SELECT tx.symbol || '/' || ty.symbol FROM liquidity_pools lp " +
                        "JOIN tokens tx ON tx.id = lp.token_x_id " +
                        "JOIN tokens ty ON ty.id = lp.token_y_id WHERE lp.id = ?::uuid",
                        String.class, id.toString());
                return s != null ? s : "";
            } catch (RuntimeException e) {
                return "";
            }
        });
        return v.isEmpty() ? null : v;
    }
}
