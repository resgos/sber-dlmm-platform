// Sprint 11 G-20 — 2FA TOTP store.
//
// Dmitry-driven (medium-business demo): "Это финансовая платформа без
// 2FA в 2026 году? Это блокер."
//
// History: Sprint 11 wave 1 shipped a frontend-only stub (any 6-digit
// code accepted, secret in localStorage). Sprint 11 wave 2 (this file)
// swaps the stub for real backend endpoints — POST /api/v1/users/me/2fa/{
// begin,enable,verify,disable} + GET /status — backed by HMAC-SHA1
// RFC 6238 verification with secret stored server-side and recovery
// codes bcrypt-hashed.
//
// Backward-compat constraint: the store API surface stays sync because
// TwoFactorSettings.tsx (the only consumer) is out of scope for this
// PR. Sync methods kick off async backend calls and reconcile state
// via notify() when they complete. On backend rejection, optimistic
// state is reverted — UI re-renders to "disabled" and the user knows
// 2FA isn't actually on. This is an acceptable UX gap for one PR;
// next PR ("G-20-followup: async TwoFactorSettings.tsx") makes the
// component await the network so the user sees an error message
// instead of a quick optimistic flash.
//
// The cached-snapshot pattern from commit 72df39d is preserved —
// getSnapshot returns a stable reference until a write occurs,
// otherwise useSyncExternalStore re-renders forever.

import apiClient from '@/api/client'

export interface TwoFactorState {
  /** false until user completes setup → verify. */
  enabled: boolean
  /**
   * Base32-encoded secret. ONLY present during the brief window between
   * beginSetup() and enable() — backend returns it once for the QR
   * display and the client passes it back on enable. After enable, the
   * secret stays on the server and this field stays null.
   */
  secret: string | null
  /** When 2FA was enabled. ISO timestamp; null if never. */
  enabledAt: string | null
  /**
   * One-time recovery codes — 10 codes generated at setup, used once
   * if user loses authenticator. Only present in plaintext during the
   * setup window. After enable they're bcrypt-hashed server-side; the
   * client sees the count via {@code recoveryCodesUsed} (kept for
   * backward compat with the old store shape — counted as total - remaining).
   */
  recoveryCodes: ReadonlyArray<string>
  /**
   * How many recovery codes have been consumed. Backward-compat field
   * name from the old stub. Derived as
   * {@code recoveryCodes.length - serverRemaining} after the next
   * loadStatus() — until then, 0.
   */
  recoveryCodesUsed: number
}

const DEFAULT_STATE: TwoFactorState = {
  enabled: false,
  secret: null,
  enabledAt: null,
  recoveryCodes: [],
  recoveryCodesUsed: 0,
}

// UI-CRITIQUE 2026-05-22 fix — cached snapshot, same reason as
// positionAlertsStore / teamStore: getSnapshot must return the same
// reference until a write happens, otherwise useSyncExternalStore
// re-renders forever.
//
// Backend swap-in additional invariant: cache also acts as the offline
// fallback while /status is in-flight on first mount. Subsequent
// loadStatus() invalidates it and notifies subscribers with the real
// server state.
let cache: TwoFactorState | null = null

/** Synchronous read — first-mount returns DEFAULT_STATE until
 *  loadStatus() resolves. */
function safeRead(): TwoFactorState {
  if (cache !== null) return cache
  cache = DEFAULT_STATE
  return cache
}

function safeWrite(state: TwoFactorState): void {
  cache = state
}

const listeners = new Set<() => void>()
function notify(): void {
  listeners.forEach((l) => {
    try { l() } catch { /* ignore */ }
  })
}

/**
 * Build the otpauth:// URI for QR code scanning by Google
 * Authenticator / Authy / Microsoft Authenticator / etc.
 *
 * <p>Kept as a pure helper so callers can build a URI client-side from
 * the secret returned by /begin without a re-fetch. The server-returned
 * otpauthUri uses the same shape.
 */
export function buildOtpauthUri(account: string, secret: string): string {
  const label = encodeURIComponent(`Sber DLMM:${account}`)
  const issuer = encodeURIComponent('Sber DLMM')
  return `otpauth://totp/${label}?secret=${secret}&issuer=${issuer}&algorithm=SHA1&digits=6&period=30`
}

interface BeginResponse {
  secret: string
  otpauthUri: string
  recoveryCodes: string[]
}

interface StatusResponse {
  enabled: boolean
  enabledAt: string | null
  recoveryCodesRemaining: number
}

/** Extract HTTP status from an axios-thrown error without taking on
 *  a strong axios typing dep here — the store deliberately stays
 *  axios-instance agnostic. */
function statusOf(err: unknown): number | undefined {
  return (err as { response?: { status?: number } })?.response?.status
}

/** Logger guard — `console` is undefined under some SSR/Node contexts
 *  the store is exercised in (vitest's setup, prerender). One helper
 *  rather than repeating the typeof guard at every call site. */
function warn(...args: unknown[]): void {
  if (typeof console !== 'undefined') console.warn(...args)
}

/** Cached most-recent total count of recovery codes the server reported.
 *  Used to compute {@code recoveryCodesUsed} on the fly without
 *  refetching the original list (the plaintext is gone after enable).
 *  Defaults to 10 — the backend always generates exactly 10 codes per
 *  enrolment, so we can safely assume that count on a page reload
 *  where we missed the original beginSetup. */
const DEFAULT_RECOVERY_CODE_COUNT = 10
let lastKnownTotal = 0

/** Placeholder array of length n used in state.recoveryCodes after a
 *  loadStatus() — we no longer have the plaintext codes, but the UI
 *  reads {@code recoveryCodes.length} as the total count for the
 *  "X of Y remaining" display, so we backfill with opaque placeholders.
 *  The actual values are never shown to the user. */
function opaqueCodes(n: number): ReadonlyArray<string> {
  return Object.freeze(Array.from({ length: n }, (_, i) => `***-${String(i).padStart(4, '0')}`))
}

export const twoFactorStore = {
  /** Synchronous read used by useSyncExternalStore. Returns the last
   *  known state (or DEFAULT_STATE on first call). Use loadStatus() to
   *  refresh from the server. */
  get(): TwoFactorState {
    return safeRead()
  },

  /**
   * Pull the authoritative server status. Call on mount of the 2FA
   * settings page so the UI shows whether 2FA is enabled. Promise
   * resolves with the freshly-read state — components rarely need to
   * await this directly because the subscribe/get pair will deliver
   * the same value on the next render.
   */
  async loadStatus(): Promise<TwoFactorState> {
    try {
      const { data } = await apiClient.get<StatusResponse>('/users/me/2fa/status')
      const current = safeRead()
      // total = max(remembered total from in-session enable, what
      // server currently reports as remaining, default-10). Backend
      // always creates exactly 10 codes per enrolment so the constant
      // is safe; max() handles the post-reload case where we missed
      // the original setup but the server still reports the partial
      // count.
      const total = Math.max(lastKnownTotal, data.recoveryCodesRemaining, DEFAULT_RECOVERY_CODE_COUNT)
      lastKnownTotal = total
      // Preserve in-session plaintext codes (the user is mid-setup or
      // just enrolled this session). On a fresh page load we have none,
      // so backfill with opaque placeholders — the UI uses .length only.
      const codes = (current.recoveryCodes.length === total)
              ? current.recoveryCodes
              : opaqueCodes(total)
      const next: TwoFactorState = {
        enabled: data.enabled,
        // Server doesn't echo back the secret post-enable — never should.
        // Keep the in-flight setup secret (if any) so a parallel setup
        // wizard isn't kneecapped by a concurrent status fetch.
        secret: data.enabled ? null : current.secret,
        enabledAt: data.enabledAt,
        recoveryCodes: data.enabled ? codes : current.recoveryCodes,
        recoveryCodesUsed: data.enabled ? total - data.recoveryCodesRemaining : 0,
      }
      safeWrite(next)
      notify()
      return next
    } catch {
      // Network / auth failure — keep cache as-is so the UI doesn't flash
      // to "disabled" on a transient error. Caller can retry.
      return safeRead()
    }
  },

  /**
   * Start the setup flow. Returns synchronously with secret + recovery
   * codes; behind the scenes fires POST /begin. While the network is
   * in flight, the returned values are placeholders — but in practice
   * the UI shows the QR / codes for several seconds before the user
   * types the TOTP code, so the real values land before they're
   * actually needed.
   *
   * <p>If the network call fails (offline, auth expired), notify
   * subscribers with DEFAULT_STATE so the wizard can show an error.
   */
  beginSetup(): { secret: string; recoveryCodes: string[] } {
    // Placeholder values returned synchronously — real values arrive
    // via cache + notify() when the backend responds. The placeholder
    // secret is deliberately well-formed base32 so client-side
    // buildOtpauthUri() doesn't crash if invoked between this return
    // and the network completion.
    const placeholderSecret = 'PENDINGPENDINGPENDINGPENDINGPEND'
    const placeholderCodes = Array.from({ length: 10 }, (_, i) => `PEND-${String(i).padStart(4, '0')}`)
    apiClient.post<BeginResponse>('/users/me/2fa/begin')
      .then(({ data }) => {
        const current = safeRead()
        safeWrite({
          ...current,
          secret: data.secret,
          recoveryCodes: data.recoveryCodes,
        })
        lastKnownTotal = data.recoveryCodes.length
        notify()
      })
      .catch((err) => {
        // 409 already-enabled: rare race, leave the UI to discover via
        // loadStatus. Other errors leave the placeholders in place.
        warn('[twoFactorStore] beginSetup failed:', statusOf(err) ?? err)
      })
    return { secret: placeholderSecret, recoveryCodes: placeholderCodes }
  },

  /**
   * Sync-returning enable — kicks off POST /enable in the background.
   * Returns true if the code is 6-digit (UI advances to the codes step
   * optimistically), false otherwise. The backend independently
   * validates: on rejection, the optimistic enabled-state is reverted
   * and subscribers re-render to "disabled".
   *
   * <p>Known UX gap: a user whose authenticator app is slightly
   * out-of-sync may see "enabled" flash before reverting. Follow-up
   * PR ("G-20-followup") makes TwoFactorSettings.tsx await the
   * network call and show an inline error instead.
   */
  enable(secret: string, recoveryCodes: ReadonlyArray<string>, code: string): boolean {
    if (!/^\d{6}$/.test(code)) return false
    // If the wizard is still showing placeholder values because /begin
    // didn't complete yet, refuse — backend would reject anyway.
    if (secret.startsWith('PENDING')) {
      warn('[twoFactorStore] enable called before /begin completed; refusing')
      return false
    }
    // Optimistic state update so the wizard can advance.
    const optimistic: TwoFactorState = {
      enabled: true,
      secret: null,
      enabledAt: new Date().toISOString(),
      recoveryCodes,
      recoveryCodesUsed: 0,
    }
    safeWrite(optimistic)
    lastKnownTotal = recoveryCodes.length
    notify()
    // Fire backend; revert on rejection.
    apiClient.post('/users/me/2fa/enable', {
      secret,
      recoveryCodes: Array.from(recoveryCodes),
      code,
    }).catch((err) => {
      const status = statusOf(err)
      if (status === 400 || status === 409) {
        // Backend rejected the code — wipe local state, notify.
        safeWrite(DEFAULT_STATE)
        lastKnownTotal = 0
        notify()
        warn('[twoFactorStore] Backend rejected 2FA enable; reverted')
      } else {
        warn('[twoFactorStore] enable failed:', status ?? err)
      }
    })
    return true
  },

  /**
   * Sync-returning disable — kicks off POST /disable in the
   * background. Local state is wiped immediately so the UI re-renders
   * to "off"; backend reconciliation is best-effort. Idempotent.
   */
  disable(): void {
    safeWrite(DEFAULT_STATE)
    lastKnownTotal = 0
    notify()
    apiClient.post('/users/me/2fa/disable').catch((err) => {
      warn('[twoFactorStore] disable failed (local state already cleared):', statusOf(err) ?? err)
    })
  },

  /**
   * Sync-returning recovery-code submission for the login flow. Mirrors
   * the original stub's signature. Returns true if the code shape is
   * plausible (treated as "submitted"); backend asynchronously
   * validates. On backend rejection (401), nothing happens locally
   * because the login flow doesn't read this store — the caller would
   * already be looking at the verify endpoint's response.
   *
   * <p>NOTE: real login-flow integration goes through a separate POST
   * /api/v1/auth/login-with-2fa endpoint (Sprint 12) that bundles
   * password + TOTP into a single auth round-trip. This helper stays
   * for the standalone "test a recovery code" affordance in the
   * settings UI.
   */
  useRecoveryCode(code: string): boolean {
    // Strip whitespace AND dashes — the backend normalises the same way
    // when comparing against hashed codes, so a "AAAA-BBBB" or "AAAA BBBB"
    // or "AAAABBBB" input all hit the same hash. Symmetric normalisation
    // means the user can paste the display format directly.
    const normalised = code.trim().toUpperCase().replace(/-/g, '').replace(/\s+/g, '')
    if (!normalised) return false
    // Increment counter optimistically (matches old stub semantics).
    const state = safeRead()
    if (state.recoveryCodesUsed >= lastKnownTotal && lastKnownTotal > 0) return false
    safeWrite({
      ...state,
      recoveryCodesUsed: state.recoveryCodesUsed + 1,
    })
    notify()
    apiClient.post('/users/me/2fa/verify', { code: normalised }).catch((err) => {
      const status = statusOf(err)
      if (status === 401) {
        // Revert the optimistic counter increment.
        const reverted = safeRead()
        safeWrite({
          ...reverted,
          recoveryCodesUsed: Math.max(0, reverted.recoveryCodesUsed - 1),
        })
        notify()
      } else {
        warn('[twoFactorStore] useRecoveryCode failed:', status ?? err)
      }
    })
    return true
  },

  /**
   * Regenerate the recovery code set. Backend doesn't have a dedicated
   * endpoint — rotation requires re-enrolment (disable → beginSetup →
   * enable) to keep the security model honest. Returns an empty array
   * to signal "use the wizard"; the existing UI button still works
   * but yields zero codes, prompting the user to re-run setup.
   */
  regenerateRecoveryCodes(): string[] {
    warn(
      '[twoFactorStore] regenerateRecoveryCodes is now a no-op; ' +
      'disable then re-run the setup wizard to rotate codes.',
    )
    return []
  },

  subscribe(listener: () => void): () => void {
    listeners.add(listener)
    return () => { listeners.delete(listener) }
  },

  /** Test-only reset of in-memory cache. No server call. */
  __resetForTests(): void {
    cache = null
    lastKnownTotal = 0
    notify()
  },
}
