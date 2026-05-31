import apiClient from './client'
import { fromRaw } from './scale'

/**
 * Sprint 17 — LP-farming rewards (Spasibo/SSPAS). Amounts come back raw (×10⁴);
 * scale to human at the boundary like everything else.
 */
export interface FarmPoolReward {
  poolId: string
  rewardTokenId: string
  unclaimed: number
  claimed: number
}

export interface FarmRewardSummary {
  totalUnclaimed: number
  totalClaimed: number
  pools: FarmPoolReward[]
}

export const farming = {
  getMyRewards: async (): Promise<FarmRewardSummary> => {
    const { data } = await apiClient.get('/pools/farming/me')
    return {
      totalUnclaimed: fromRaw(data.totalUnclaimed ?? 0),
      totalClaimed: fromRaw(data.totalClaimed ?? 0),
      pools: (data.pools ?? []).map((p: { poolId: string; rewardTokenId: string; unclaimed: number; claimed: number }) => ({
        poolId: p.poolId,
        rewardTokenId: p.rewardTokenId,
        unclaimed: fromRaw(p.unclaimed ?? 0),
        claimed: fromRaw(p.claimed ?? 0),
      })),
    }
  },

  /** Claim all accrued reward; returns the human-scaled amount credited. */
  claimRewards: async (): Promise<number> => {
    const { data } = await apiClient.post('/pools/farming/claim')
    return fromRaw(data.claimed ?? 0)
  },
}
