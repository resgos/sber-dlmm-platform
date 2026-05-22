// Sprint 10 (new feature) — Auto-claim fees.
//
// User-side opt-in: when enabled, the tab automatically calls
// fees.claimFees(positionId) for every position whose unclaimed
// total exceeds a user-set threshold. Pure-frontend MVP — no
// scheduler / cron / Kafka.
//
// Why frontend: the user already has a tab open with the positions
// query refreshing every 30s; the marginal cost of calling claim is
// near-zero, and we avoid a backend table + scheduled job until
// product proves out. The backend equivalent (Sprint 11) would be
// a per-user policy row + scheduled checker; the rule shape here
// matches what the API would expose.
//
// Safety:
//   - Disabled by default (off until the user actively turns it on).
//   - Per-claim cooldown 1h (don't fire two claims for the same
//     position back-to-back even if data refreshes faster).
//   - Per-claim audit entry kept in-memory (last 20) so the user can
//     verify in the drawer that the watcher is doing what they expect.
//
// Migration to backend (Sprint 11): POST /api/v1/users/auto-claim-policy
// with { enabled, threshold }. Scheduler runs the same check daily;
// frontend store flips to "this is what's configured server-side"
// and the per-claim history comes from fee_accruals.claimed_at.

const STORAGE_KEY = 'dlmm.user.autoClaim'

export interface AutoClaimPolicy {
  enabled: boolean
  /** Minimum unclaimed fee total (X+Y in base units) before auto-claim fires. */
  threshold: number
}

const DEFAULT_POLICY: AutoClaimPolicy = {
  enabled: false,
  threshold: 1000, // 1k base units — sensible for SRUB-quoted seed pools
}

// History of auto-fired claims, kept in-memory only (a refresh wipes
// it). The Profile drawer reads this for the audit list.
const history: Array<{ positionId: string; symbol: string; amount: number; firedAt: string }> = []
const HISTORY_MAX = 20

// Cooldown per position to dedupe back-to-back fires.
const PER_POSITION_COOLDOWN_MS = 60 * 60 * 1000
const lastFiredAtByPosition = new Map<string, number>()

function safeRead(): AutoClaimPolicy {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return DEFAULT_POLICY
    const parsed = JSON.parse(raw)
    if (typeof parsed?.enabled !== 'boolean' || typeof parsed?.threshold !== 'number') {
      return DEFAULT_POLICY
    }
    return parsed as AutoClaimPolicy
  } catch {
    return DEFAULT_POLICY
  }
}

function safeWrite(p: AutoClaimPolicy): void {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(p)) } catch { /* ignore */ }
}

const listeners = new Set<() => void>()
function notify(): void {
  listeners.forEach((l) => {
    try { l() } catch { /* ignore */ }
  })
}

export const autoClaimStore = {
  get(): AutoClaimPolicy {
    return safeRead()
  },
  set(p: AutoClaimPolicy): void {
    safeWrite(p)
    notify()
  },
  subscribe(listener: () => void): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },

  /** True if the position is past its per-position cooldown window. */
  canFire(positionId: string): boolean {
    const last = lastFiredAtByPosition.get(positionId)
    if (!last) return true
    return Date.now() - last >= PER_POSITION_COOLDOWN_MS
  },

  /** Record a successful claim — bookkeeping for cooldown + history. */
  recordFired(positionId: string, symbol: string, amount: number): void {
    lastFiredAtByPosition.set(positionId, Date.now())
    history.unshift({ positionId, symbol, amount, firedAt: new Date().toISOString() })
    if (history.length > HISTORY_MAX) history.length = HISTORY_MAX
    notify()
  },

  history(): ReadonlyArray<{ positionId: string; symbol: string; amount: number; firedAt: string }> {
    return history
  },
}
