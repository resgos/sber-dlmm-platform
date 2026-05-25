// Sprint 10 (frontend MVP) → Sprint 12 G-16 (backend swap-in).
//
// Auto-claim policy now lives server-side (fee-service @Scheduled
// + ShedLock; per-user policy table; auto_claim_log audit). This
// store is now a *thin local cache* of the server's policy:
//
//   - Reads (get / canFire / isCappedToday) are sync and return the
//     last-known cached value. First-time access triggers a fetch in
//     the background and notifies subscribers once the response lands.
//   - Writes (set / toggleSkipPool / etc.) update the cache
//     optimistically, fire-and-forget the PUT, revert on rejection.
//     Same pattern G-20 lands for twoFactorStore (PR #4).
//   - History (history()) returns the last-known server history; the
//     watcher hook is now a no-op by default (gate on
//     `useFrontendFallback`) — the scheduler is the source of truth.
//
// The frontend watcher hook (useAutoClaimWatcher.ts) is kept as a
// fast-path-fallback (off unless explicitly enabled). The recordFired
// + cooldown + dailyCap logic stays because tests + the optional
// fallback rely on it; with `useFrontendFallback=false` (default), the
// watcher returns early and never calls recordFired.
//
// Critical invariant — `let cache` at module level, null on
// __resetSideStateForTests, updated on every read/write. Matches
// `06f964c` (the test-fix that gives useSyncExternalStore stable
// references). DO NOT change to const + per-call JSON.parse.

import { fees, type AutoClaimPolicyWire, type AutoClaimLogEntry } from '@/api/services'

const STORAGE_KEY = 'dlmm.user.autoClaim'

export interface AutoClaimPolicy {
  enabled: boolean
  /** Minimum unclaimed fee total (X+Y in base units) before auto-claim fires. */
  threshold: number
  /**
   * Sprint 10 wave 3 — daily cap. Max number of auto-claims that
   * may fire in any rolling 24h window. Hard safety net. 0 = unlimited
   * (default 20 is generous for typical use).
   */
  dailyCap: number
  /**
   * Sprint 10 wave 3 — pool exception list. Auto-claim is skipped
   * for any position belonging to a pool whose id is in this set.
   */
  skipPoolIds: string[]
  /**
   * Sprint 12 G-16 — keep the frontend watcher running as a fast-path
   * even though the backend scheduler is now the source of truth.
   * Default false: backend handles it. Setting true preserves the
   * Sprint 10 MVP behaviour (browser-side claim within seconds of
   * meeting the threshold, vs. up-to-60s for the scheduler).
   */
  useFrontendFallback?: boolean
}

const DEFAULT_POLICY: AutoClaimPolicy = {
  enabled: false,
  threshold: 1000, // 1k base units — sensible for SRUB-quoted seed pools
  dailyCap: 20,
  skipPoolIds: [],
  useFrontendFallback: false,
}

// History of auto-fired claims, frontend-fallback only (the backend
// scheduler writes to auto_claim_log; the Profile drawer reads that
// via fees.getAutoClaimHistory()). Kept here so the in-tab fallback
// path retains its audit trail without a server round-trip.
//
// `let` (not `const`) so the array reference changes on every write —
// useSyncExternalStore needs a fresh reference to re-render
// subscribers. UI-CRITIQUE 2026-05-22 fix.
let history: Array<{ positionId: string; symbol: string; amount: number; firedAt: string }> = []
const HISTORY_MAX = 20

// Cooldown per position to dedupe back-to-back fires (frontend-fallback
// only; the backend scheduler enforces the same window via auto_claim_log).
const PER_POSITION_COOLDOWN_MS = 60 * 60 * 1000
const lastFiredAtByPosition = new Map<string, number>()

// Cached snapshot — see file header for the why.
let cache: AutoClaimPolicy | null = null

// Sprint 12 G-16 — track whether we've kicked off the initial server
// fetch. We avoid the fetch in test contexts where vitest's
// setup.ts blanks localStorage and re-imports the store many times.
let serverHydrationStarted = false

function isBrowser(): boolean {
  // Vitest jsdom has window but no axios mock by default — callers
  // pass mocked api modules in tests. We still gate on the env so a
  // node-only build doesn't crash.
  return typeof window !== 'undefined'
}

function wireToPolicy(wire: AutoClaimPolicyWire): AutoClaimPolicy {
  return {
    enabled: !!wire.enabled,
    threshold: typeof wire.thresholdAmount === 'number' ? wire.thresholdAmount : 0,
    dailyCap: typeof wire.dailyCap === 'number' ? wire.dailyCap : 0,
    skipPoolIds: Array.isArray(wire.skipPoolIds) ? wire.skipPoolIds : [],
    useFrontendFallback: false,
  }
}

function policyToWire(p: AutoClaimPolicy): AutoClaimPolicyWire {
  return {
    enabled: p.enabled,
    thresholdAmount: p.threshold,
    dailyCap: p.dailyCap,
    skipPoolIds: p.skipPoolIds,
  }
}

function safeRead(): AutoClaimPolicy {
  if (cache !== null) return cache
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      cache = DEFAULT_POLICY
      return cache
    }
    const parsed = JSON.parse(raw)
    if (typeof parsed?.enabled !== 'boolean' || typeof parsed?.threshold !== 'number') {
      cache = DEFAULT_POLICY
      return cache
    }
    cache = {
      enabled: parsed.enabled,
      threshold: parsed.threshold,
      dailyCap: typeof parsed.dailyCap === 'number' ? parsed.dailyCap : DEFAULT_POLICY.dailyCap,
      skipPoolIds: Array.isArray(parsed.skipPoolIds)
        ? parsed.skipPoolIds.filter((x: unknown) => typeof x === 'string')
        : [],
      useFrontendFallback: typeof parsed.useFrontendFallback === 'boolean' ? parsed.useFrontendFallback : false,
    }
    return cache
  } catch {
    cache = DEFAULT_POLICY
    return cache
  }
}

function safeWrite(p: AutoClaimPolicy): void {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(p)) } catch { /* ignore */ }
  cache = p
}

function policyEquals(a: AutoClaimPolicy, b: AutoClaimPolicy): boolean {
  if (a.enabled !== b.enabled) return false
  if (a.threshold !== b.threshold) return false
  if (a.dailyCap !== b.dailyCap) return false
  if (a.useFrontendFallback !== b.useFrontendFallback) return false
  if (a.skipPoolIds.length !== b.skipPoolIds.length) return false
  for (let i = 0; i < a.skipPoolIds.length; i++) {
    if (a.skipPoolIds[i] !== b.skipPoolIds[i]) return false
  }
  return true
}

const listeners = new Set<() => void>()
function notify(): void {
  listeners.forEach((l) => {
    try { l() } catch { /* ignore */ }
  })
}

/**
 * Sprint 12 G-16 — pull the current policy from the server.
 * Updates the cache + notifies on success. Failures are swallowed
 * (we keep the localStorage-cached value as the fallback so the UI
 * stays usable in offline / degraded modes).
 */
function hydrateFromServer(): void {
  if (!isBrowser()) return
  if (serverHydrationStarted) return
  serverHydrationStarted = true
  fees.getAutoClaimPolicy()
    .then((wire) => {
      const current = safeRead()
      const next: AutoClaimPolicy = {
        ...wireToPolicy(wire),
        // useFrontendFallback is tab-specific — server doesn't persist it.
        useFrontendFallback: current.useFrontendFallback,
      }
      // Skip the write/notify if the server returned the same values —
      // otherwise every page load notifies subscribers with a new object
      // reference and triggers a no-op re-render.
      if (policyEquals(current, next)) return
      safeWrite(next)
      notify()
    })
    .catch(() => {
      // Network / auth failure. Keep the localStorage cache.
      // Reset the hydration latch so the next render can retry.
      serverHydrationStarted = false
    })
}

/**
 * Push the policy to the server (fire-and-forget). On failure, revert
 * the cache to {@code previous} and notify. Returns a promise for
 * tests that want to await.
 */
function pushToServer(next: AutoClaimPolicy, previous: AutoClaimPolicy): Promise<void> {
  if (!isBrowser()) return Promise.resolve()
  return fees.putAutoClaimPolicy(policyToWire(next))
    .then((echoed) => {
      // Re-set with the server-echoed values so the cache matches truth
      // (server may have normalised, eg trimmed whitespace). Only notify
      // if the echo actually differs from what we just wrote.
      const echoedPolicy: AutoClaimPolicy = {
        ...wireToPolicy(echoed),
        useFrontendFallback: next.useFrontendFallback,
      }
      if (policyEquals(next, echoedPolicy)) return
      safeWrite(echoedPolicy)
      notify()
    })
    .catch(() => {
      // Revert to the pre-write snapshot so a failed save doesn't
      // leak into the next read.
      safeWrite(previous)
      notify()
    })
}

export const autoClaimStore = {
  get(): AutoClaimPolicy {
    const local = safeRead()
    hydrateFromServer()
    return local
  },

  set(p: AutoClaimPolicy): void {
    const previous = safeRead()
    safeWrite(p)
    notify()
    void pushToServer(p, previous)
  },

  subscribe(listener: () => void): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },

  /**
   * True if the position is past its per-position cooldown window AND
   * the rolling 24h cap hasn't been hit. Used by the
   * frontend-fallback watcher only; the backend scheduler runs its
   * own version using auto_claim_log.
   */
  canFire(positionId: string): boolean {
    const last = lastFiredAtByPosition.get(positionId)
    if (last && Date.now() - last < PER_POSITION_COOLDOWN_MS) return false
    return !this.isCappedToday()
  },

  isCappedToday(): boolean {
    const p = safeRead()
    if (p.dailyCap <= 0) return false
    const cutoff = Date.now() - 24 * 60 * 60 * 1000
    const count = history.filter((h) => new Date(h.firedAt).getTime() >= cutoff).length
    return count >= p.dailyCap
  },

  /** Used by the Profile UI for the "X / Y сегодня" hint. */
  countLast24h(): number {
    const cutoff = Date.now() - 24 * 60 * 60 * 1000
    return history.filter((h) => new Date(h.firedAt).getTime() >= cutoff).length
  },

  /** Record a successful claim — bookkeeping for cooldown + history. */
  recordFired(positionId: string, symbol: string, amount: number): void {
    lastFiredAtByPosition.set(positionId, Date.now())
    history = [{ positionId, symbol, amount, firedAt: new Date().toISOString() }, ...history].slice(0, HISTORY_MAX)
    notify()
  },

  /** Sprint 10 wave 3 — pool exception toggle. */
  toggleSkipPool(poolId: string): void {
    const p = safeRead()
    const next = p.skipPoolIds.includes(poolId)
      ? p.skipPoolIds.filter((x) => x !== poolId)
      : [...p.skipPoolIds, poolId]
    this.set({ ...p, skipPoolIds: next })
  },

  isPoolSkipped(poolId: string): boolean {
    return safeRead().skipPoolIds.includes(poolId)
  },

  history(): ReadonlyArray<{ positionId: string; symbol: string; amount: number; firedAt: string }> {
    return history
  },

  /**
   * Sprint 12 G-16 — fetch the server-side audit log. Returns the
   * promise so the caller (Profile drawer history tab) can show a
   * loading state.
   */
  fetchServerHistory(limit = 20): Promise<AutoClaimLogEntry[]> {
    if (!isBrowser()) return Promise.resolve([])
    return fees.getAutoClaimHistory(limit).catch(() => [])
  },

  /**
   * Test-only reset of the in-memory side state (history + cooldown
   * map + cache + server-hydration latch).
   */
  __resetSideStateForTests(): void {
    history = []
    lastFiredAtByPosition.clear()
    cache = null
    serverHydrationStarted = false
    notify()
  },
}
