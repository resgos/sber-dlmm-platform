package com.sber.dlmm.pool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "position_bins")
@IdClass(PositionBinId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PositionBin {

    @Id
    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    @Id
    @Column(name = "bin_id", nullable = false)
    private int binId;

    @Column(name = "liquidity_shares", nullable = false)
    private long liquidityShares;
}
