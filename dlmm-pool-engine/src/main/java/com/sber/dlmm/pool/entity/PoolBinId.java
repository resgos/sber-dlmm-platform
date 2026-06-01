package com.sber.dlmm.pool.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite primary key for {@link PoolBin}: (poolId, binId). Referenced by
 * {@code @IdClass(PoolBinId.class)} on the entity and used as the id type of
 * {@code PoolBinRepository}.
 *
 * <p>Field names and types must mirror the {@code @Id} fields on {@link PoolBin}
 * exactly, and JPA requires this class be {@link Serializable} with a correct
 * {@code equals}/{@code hashCode} — both supplied here by Lombok
 * {@code @EqualsAndHashCode} (so equal keys collapse in identity maps and
 * second-level cache lookups). No explicit methods.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PoolBinId implements Serializable {
    /** Owning pool id; matches {@link PoolBin#getPoolId()}. */
    private UUID poolId;
    /** Absolute bin id; matches {@link PoolBin#getBinId()}. */
    private int binId;
}
