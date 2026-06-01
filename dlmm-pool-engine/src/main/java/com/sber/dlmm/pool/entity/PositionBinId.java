package com.sber.dlmm.pool.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite primary key for {@link PositionBin}: (positionId, binId). Wired via
 * {@code @IdClass(PositionBinId.class)} and used as the repository id type.
 *
 * <p>Mirrors the entity's {@code @Id} fields; JPA requires {@link Serializable}
 * plus a correct {@code equals}/{@code hashCode}, provided by Lombok
 * {@code @EqualsAndHashCode}. No explicit methods.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PositionBinId implements Serializable {
    /** Owning position id; matches {@link PositionBin#getPositionId()}. */
    private UUID positionId;
    /** Bin id; matches {@link PositionBin#getBinId()}. */
    private int binId;
}
