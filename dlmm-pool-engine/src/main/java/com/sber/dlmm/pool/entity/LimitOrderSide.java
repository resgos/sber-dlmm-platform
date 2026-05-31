package com.sber.dlmm.pool.entity;

/**
 * Direction of a {@link LimitOrder}, expressed relative to the pool's token X
 * (the base asset; price is quoted as token Y per 1 token X).
 *
 * <ul>
 *   <li>{@code BUY}  — buy X with Y. Escrows Y; fills when price ≤ limitPrice
 *       (i.e. when X gets cheap enough). Output is X = amountIn / limitPrice.</li>
 *   <li>{@code SELL} — sell X for Y. Escrows X; fills when price ≥ limitPrice
 *       (i.e. when X gets expensive enough). Output is Y = amountIn × limitPrice.</li>
 * </ul>
 */
public enum LimitOrderSide {
    BUY,
    SELL
}
