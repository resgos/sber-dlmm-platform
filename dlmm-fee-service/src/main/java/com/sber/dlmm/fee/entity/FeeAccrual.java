package com.sber.dlmm.fee.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "fee_accruals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeeAccrual {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_id", nullable = false)
    private UUID tokenId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "tx_id")
    private UUID txId;

    @Builder.Default
    @Column(name = "claimed", nullable = false)
    private boolean claimed = false;

    @Column(name = "accrued_at", nullable = false)
    private LocalDateTime accruedAt;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @PrePersist
    public void prePersist() {
        if (accruedAt == null) {
            accruedAt = LocalDateTime.now();
        }
    }
}
