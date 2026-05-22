import { useEffect, useRef } from 'react'
import { useMutation } from '@tanstack/react-query'
import { autoClaimStore } from '@/store/autoClaimStore'
import { fees } from '@/api/services'
import type { Position } from '@/api/types'

// Sprint 10 (new feature) — auto-claim watcher hook.
//
// Mounted on PositionsPage. On every (positions) refresh, walks the
// active positions and fires fees.claimFees() for any whose unclaimed
// total >= the user's threshold AND not on cooldown. Sequential — one
// inflight claim at a time, so we don't hammer the server.

export interface AutoClaimContext {
  position: Position
  amount: number
}

/** Pure check — exported for the test suite. */
export function shouldFire(position: Position, threshold: number, enabled: boolean): boolean {
  if (!enabled) return false
  if (!position.isActive) return false
  const total = position.unclaimedFeeX + position.unclaimedFeeY
  return total >= threshold
}

/**
 * Sprint 10 wave 3 — pure preview helper. Given the current positions
 * + policy, returns the positions that WOULD fire on the next watcher
 * tick (ignoring cooldown, since the preview is "what's about to
 * happen"). Used by the AutoClaimSettings card so the user can sanity-
 * check before flipping the switch.
 */
export function previewFireable(
  positions: Position[] | undefined,
  threshold: number,
  skipPoolIds: ReadonlyArray<string>,
): Position[] {
  if (!positions) return []
  return positions.filter((p) =>
    shouldFire(p, threshold, true) && !skipPoolIds.includes(p.poolId),
  )
}

export function useAutoClaimWatcher(
  positions: Position[] | undefined,
  onClaimed?: (ctx: AutoClaimContext) => void,
): void {
  // Mutation is created once per mount. We don't surface its loading
  // state — the watcher is silent unless something fires.
  const claimMutation = useMutation({
    mutationFn: (positionId: string) => fees.claimFees({ positionId }),
  })

  // Re-entrancy guard. React Query's stale-time can call us mid-flight;
  // we skip if there's already an inflight claim to keep the order
  // deterministic and avoid the "two simultaneous claims for the same
  // position" race.
  const inFlightRef = useRef(false)

  useEffect(() => {
    if (!positions) return
    if (inFlightRef.current) return
    const policy = autoClaimStore.get()
    if (!policy.enabled) return

    // Sprint 10 wave 3 — rolling-24h cap honoured at the watcher level
    // too (not just the per-position cooldown). Belt-and-braces in case
    // a future cooldown change forgets the cap.
    if (autoClaimStore.isCappedToday()) return

    // Find the first eligible position. We deliberately do one per
    // render-cycle so the UI is never blocked on a chain of awaits;
    // the next React Query refresh will pick up the next eligible one.
    // Sprint 10 wave 3 — skip pools the user has flagged exception.
    const eligible = positions.find((p) =>
      shouldFire(p, policy.threshold, true) &&
      !policy.skipPoolIds.includes(p.poolId) &&
      autoClaimStore.canFire(p.id),
    )
    if (!eligible) return

    const symbolPair = `${eligible.tokenXSymbol}/${eligible.tokenYSymbol}`
    const amount = eligible.unclaimedFeeX + eligible.unclaimedFeeY

    inFlightRef.current = true
    claimMutation.mutate(eligible.id, {
      onSuccess: () => {
        autoClaimStore.recordFired(eligible.id, symbolPair, amount)
        onClaimed?.({ position: eligible, amount })
      },
      onSettled: () => {
        inFlightRef.current = false
      },
    })
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [positions])
}
