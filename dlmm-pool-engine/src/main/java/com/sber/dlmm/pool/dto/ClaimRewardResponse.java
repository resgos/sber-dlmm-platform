package com.sber.dlmm.pool.dto;

/**
 * Sprint 17 — result of a LP-farming claim: the total reward credited to the
 * user's balance, raw (×10⁴) reward-token units. {@code claimed == 0} means
 * there was nothing pending.
 */
public record ClaimRewardResponse(long claimed) {
}
