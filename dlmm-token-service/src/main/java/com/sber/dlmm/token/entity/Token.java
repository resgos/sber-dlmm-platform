package com.sber.dlmm.token.entity;

import com.sber.dlmm.common.enums.TokenType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Catalog row for a single tradable token (table {@code tokens}).
 *
 * <p>One row models one token the platform knows about: its display
 * {@link #name}, its globally-unique {@link #symbol} (DB unique constraint),
 * supply caps, classification ({@link TokenType}) and the mint/burn/active
 * capability flags. It is the aggregate root other token-service data hangs
 * off (a {@link UserBalance} references a token by id; pools reference token
 * symbols). It does NOT hold any per-user balance — balances live in
 * {@link UserBalance}.
 *
 * <h2>Money / scale</h2>
 * <p>{@link #totalSupply} and {@link #maxSupply} are raw integer amounts.
 * Note that per the platform amount-scale convention (#14) per-user balances
 * are stored at the uniform ×10⁴ raw scale (1 unit = 10⁻⁴ token); supply,
 * however, is deliberately left un-rescaled by the demo seed (it is
 * undisplayed and unused in trade flows), so do not assume the two share a
 * scale. The {@link #decimals} column records the token's nominal precision
 * but the engine never applies it — all amounts are raw integers.
 *
 * <p>This is a Lombok entity ({@code @Getter}/{@code @Setter}/
 * {@code @NoArgsConstructor}/{@code @AllArgsConstructor}); accessors are
 * generated. No {@code @Version} column — token rows are low-contention and
 * not optimistically locked.
 */
@Entity
@Table(name = "tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Token {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false)
    private String symbol;

    /** Nominal token precision; recorded for display only — the engine
     *  treats every amount as a raw integer and never applies it. */
    @Column(nullable = false)
    private int decimals = 8;

    /** Currently issued supply (raw integer; left un-rescaled by demo seed). */
    @Column(nullable = false)
    private long totalSupply;

    /** Hard cap on issuable supply (raw integer; same scale as totalSupply). */
    @Column(nullable = false)
    private long maxSupply;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TokenType tokenType;

    private String underlyingAsset;

    private String priceOracleId;

    @Column(nullable = false)
    private boolean mintable;

    @Column(nullable = false)
    private boolean burnable;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * JPA lifecycle callback fired before the first INSERT: stamps
     * {@link #createdAt} with the current time. The column is
     * {@code updatable = false}, so creation time is immutable thereafter.
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
