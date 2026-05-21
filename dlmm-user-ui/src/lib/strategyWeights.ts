import type { LiquidityStrategy } from '@/api/types'

/**
 * Sprint 9-DS-r4 (P1-2) — client-side mirror of
 * {@code LiquidityService.calculateDistributionWeights} so the
 * Meteora-style "what will my position look like?" preview can redraw
 * the bin chart on every strategy / range change without a round-trip.
 *
 * <p>Three strategies match the backend exactly:
 *   - SPOT: uniform weight per bin
 *   - CURVE: gaussian centred at activeBinId, σ = (max-min)/6
 *   - BID_ASK: edge-weighted (more on extremes), with a 0.05 floor so
 *     no bin is empty
 *
 * <p>All three normalise so weights sum to 1.0.
 *
 * <p>When you change the backend formula, update this function and
 * keep the test in {@code dlmm-user-ui/src/lib/__tests__/strategyWeights.test.ts}
 * (Sprint 10) in sync.
 */
export function calculateStrategyWeights(
  strategy: LiquidityStrategy,
  binMin: number,
  binMax: number,
  activeBinId: number,
): number[] {
  const numBins = binMax - binMin + 1
  if (numBins <= 0) return []

  const weights = new Array<number>(numBins).fill(0)

  switch (strategy) {
    case 'SPOT': {
      const uniform = 1 / numBins
      for (let i = 0; i < numBins; i++) weights[i] = uniform
      return weights
    }
    case 'CURVE': {
      let sigma = (binMax - binMin) / 6
      if (sigma <= 0) sigma = 1
      let sum = 0
      for (let i = 0; i < numBins; i++) {
        const binId = binMin + i
        const dist = binId - activeBinId
        weights[i] = Math.exp(-(dist * dist) / (2 * sigma * sigma))
        sum += weights[i]
      }
      if (sum > 0) for (let i = 0; i < numBins; i++) weights[i] /= sum
      return weights
    }
    case 'BID_ASK': {
      let maxDist = Math.max(
        Math.abs(binMax - activeBinId),
        Math.abs(binMin - activeBinId),
      )
      if (maxDist <= 0) maxDist = 1
      let sum = 0
      for (let i = 0; i < numBins; i++) {
        const binId = binMin + i
        const dist = Math.abs(binId - activeBinId)
        let w = dist / maxDist
        if (w < 0.05) w = 0.05
        weights[i] = w
        sum += w
      }
      if (sum > 0) for (let i = 0; i < numBins; i++) weights[i] /= sum
      return weights
    }
  }
}
