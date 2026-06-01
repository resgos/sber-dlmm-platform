package com.sber.dlmm.common.enums;

/**
 * Shape of the liquidity distribution an LP requests when adding liquidity
 * across a range of price bins (DLMM concentrated-liquidity model).
 *
 * <p>Chosen in the user-ui {@code StrategySelector} and consumed by
 * pool-engine's liquidity service, which turns the strategy into per-bin
 * weights spread over the selected bin range. The names follow Trader Joe
 * LB / Meteora terminology.
 */
public enum LiquidityStrategy {
    /** Uniform — liquidity spread evenly across every bin in the range. */
    SPOT,
    /** Concentrated around the active bin — most weight near the current
     *  price, tapering toward the range edges. */
    CURVE,
    /** Edge-weighted — liquidity pushed toward the range ends (bid and ask
     *  sides), leaving the middle thin; used for grid / range-order style
     *  provisioning. */
    BID_ASK
}
