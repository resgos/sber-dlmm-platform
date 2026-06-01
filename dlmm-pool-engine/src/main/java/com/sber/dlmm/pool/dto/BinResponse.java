package com.sber.dlmm.pool.dto;

import java.math.BigDecimal;

/**
 * State of a single price bin, as listed in {@link PoolDetailResponse#bins()} to
 * render the pool's liquidity depth / order-book view.
 *
 * <p>Each bin holds reserves at a fixed price and satisfies the invariant
 * {@code reserveX·price + reserveY == liquidity}.
 *
 * @param binId             the bin's id; lower ids are lower prices
 * @param price             the bin's fixed price (token_y per token_x ratio), a real
 *                          decimal — NOT scaled
 * @param liquidity         the bin's total liquidity in canonical L-units
 *                          ({@code reserveX·price + reserveY}); NOT a token amount
 * @param reserveX          X-token reserve held in this bin, raw integer at 10⁻⁴ scale
 * @param reserveY          Y-token reserve held in this bin, raw integer at 10⁻⁴ scale
 * @param compositionFactor share of the bin's value currently held as Y vs X
 *                          (0 = all X, 1 = all Y); a decimal fraction, NOT scaled.
 *                          The active bin is mixed, bins fully on one side of the
 *                          price are 0 or 1
 */
public record BinResponse(
        int binId,
        BigDecimal price,
        long liquidity,
        long reserveX,
        long reserveY,
        BigDecimal compositionFactor
) {
}
