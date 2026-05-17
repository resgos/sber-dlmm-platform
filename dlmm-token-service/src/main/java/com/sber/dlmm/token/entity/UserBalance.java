package com.sber.dlmm.token.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_balances")
@IdClass(UserBalanceId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserBalance {

    @Id
    @Column(nullable = false)
    private UUID userId;

    @Id
    @Column(nullable = false)
    private UUID tokenId;

    @Column(nullable = false)
    private long available;

    @Column(nullable = false)
    private long locked;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Last time the custody-fee scheduled job touched this row.
     * NULL for rows that have never been accrued — the job treats
     * those as "fresh" and uses updated_at as the start of the
     * accrual window. See CustodyFeeJob + DB-MIGRATION-CONVENTION.md.
     */
    @Column(name = "last_custody_fee_at")
    private LocalDateTime lastCustodyFeeAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
