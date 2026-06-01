package com.sber.dlmm.common.enums;

/**
 * Kind of ledger transaction recorded by the transaction-service.
 *
 * <p>Identifies the business operation behind a ledger entry; used for
 * filtering, reporting and per-type handling in settlement and AML flows.
 */
public enum TransactionType {
    /** Funds moved into a custody / platform balance. */
    DEPOSIT,
    /** Funds moved out of a custody / platform balance. */
    WITHDRAW,
    /** Token-for-token exchange through a DLMM pool. */
    SWAP,
    /** Liquidity provided to a pool (LP position opened / increased). */
    ADD_LIQUIDITY,
    /** Liquidity withdrawn from a pool (LP position decreased / closed). */
    REMOVE_LIQUIDITY,
    /** LP fees collected from accrued fee growth. */
    CLAIM_FEE,
    /** New token units issued (supply increase). */
    MINT,
    /** Token units destroyed (supply decrease). */
    BURN,
    /** Token units moved between accounts. */
    TRANSFER
}
