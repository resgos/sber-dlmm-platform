package com.sber.dlmm.pool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 16 (Meteora parity) — a DLMM-style limit order.
 *
 * <p>Meteora lets an LP place liquidity into a single bin so it fills when the
 * market crosses that price. We implement the same user-facing behaviour with a
 * simpler, safer <b>escrow-settled</b> model that doesn't perturb the pool's bin
 * invariants: on placement we deduct (escrow) {@code amountIn} of the input
 * token; a scheduled watcher ({@code LimitOrderFillWatcher}) settles the order
 * at the exact {@code limitPrice} — crediting {@code amountOut} of the output
 * token — the moment the pool's market-synced price ({@code basePrice}) crosses
 * the trigger; cancelling refunds the escrow. The pool's reserves are never
 * touched, so F-12 and the swap math are untouched.
 *
 * <p>Amounts are raw platform units (uniform ×10⁴ scale, see scale.ts); the
 * price is a real token_y-per-token_x ratio and is NOT scaled.
 */
@Entity
@Table(name = "limit_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LimitOrder {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 8)
    private LimitOrderSide side;

    /** Token the user escrowed on placement (Y for BUY, X for SELL). */
    @Column(name = "token_in_id", nullable = false)
    private UUID tokenInId;

    /** Token credited on fill (X for BUY, Y for SELL). */
    @Column(name = "token_out_id", nullable = false)
    private UUID tokenOutId;

    /** Escrowed input amount, raw units. */
    @Column(name = "amount_in", nullable = false)
    private long amountIn;

    /** Trigger price, token_y per 1 token_x (same convention as pool.basePrice). */
    @Column(name = "limit_price", nullable = false, precision = 30, scale = 18)
    private BigDecimal limitPrice;

    /** Output amount credited on fill, raw units. Computed at placement, fixed. */
    @Column(name = "amount_out", nullable = false)
    private long amountOut;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LimitOrderStatus status;

    /** Caller-supplied de-dup token; a repeat create with the same key is a no-op. */
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "filled_at")
    private LocalDateTime filledAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = LimitOrderStatus.OPEN;
        if (version == null) version = 0L;
    }
}
