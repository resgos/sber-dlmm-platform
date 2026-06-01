package com.sber.dlmm.pool.dto;

/**
 * One bin's slice of a liquidity position — how much of each token sits in a single
 * price bin and the shares it represents.
 *
 * <p>Appears as a list inside add/remove-liquidity and position responses, giving a
 * per-bin breakdown of an otherwise range-wide position.
 *
 * @param binId           the price bin's id; its price is derived from the pool's
 *                        bin step and this id
 * @param amountX         X-token reserve attributable to this bin, raw integer at
 *                        10⁻⁴ scale
 * @param amountY         Y-token reserve attributable to this bin, raw integer at
 *                        10⁻⁴ scale
 * @param liquidityShares liquidity shares the position holds in this bin (internal
 *                        L-units)
 */
public record BinAllocation(
        int binId,
        long amountX,
        long amountY,
        long liquidityShares
) {
}
