// UI mode preference store.
//
// SM-01 (2026-05-29) — promoted the old boolean `simpleMode` flag to a
// first-class `mode: 'simple' | 'pro'`. Simple mode powers the airy
// SimpleTradePage (buy/sell + basic add-liquidity, no bins/strategy/ranges)
// and hides the advanced sidebar items (Ребаланс, Команда, advanced
// liquidity). Pro mode is the full trading surface.
//
// Backward-compat: the prior version persisted `{ simpleMode: boolean }`.
// We keep `simpleMode` / `isSimpleMode()` / `setSimpleMode()` working
// (derived from `mode`) so existing readers (ProfilePage, UserLayout)
// don't break, and we migrate any stored `{ simpleMode: true }` payload
// to `{ mode: 'simple' }` on first read.
//
// Persisted to localStorage. Follows the module-level cache pattern: the
// getSnapshot accessor MUST return a STABLE reference between mutations,
// otherwise useSyncExternalStore sees "data changed" every render and
// React loops (Minified React error #185). See positionAlertsStore for
// the canonical write-up of this footgun.

const PREF_KEY = 'dlmm.user.uiPrefs'

export type UiMode = 'simple' | 'pro'

export interface UiPrefs {
  mode: UiMode
  /** Backward-compat mirror of `mode === 'simple'`. Kept on the snapshot
   *  so existing `prefs.simpleMode` reads still type-check and work. */
  simpleMode: boolean
}

// SM-01 — default mode. The codebase shipped with the advanced (Pro)
// surface as the default and we keep that to avoid surprising existing
// users / the demo flow. First-run simple-default is intentionally NOT
// turned on here (low-risk but out of scope of the current default).
const DEFAULT_MODE: UiMode = 'pro'

function makePrefs(mode: UiMode): UiPrefs {
  return { mode, simpleMode: mode === 'simple' }
}

let _cache: UiPrefs = readFromStorage()
const _subscribers = new Set<() => void>()

function readFromStorage(): UiPrefs {
  try {
    const raw = localStorage.getItem(PREF_KEY)
    if (!raw) return makePrefs(DEFAULT_MODE)
    const parsed = JSON.parse(raw)
    // New shape: { mode: 'simple' | 'pro' }.
    if (parsed?.mode === 'simple' || parsed?.mode === 'pro') {
      return makePrefs(parsed.mode)
    }
    // Legacy shape: { simpleMode: boolean } → migrate to mode.
    if (typeof parsed?.simpleMode === 'boolean') {
      return makePrefs(parsed.simpleMode ? 'simple' : 'pro')
    }
    return makePrefs(DEFAULT_MODE)
  } catch {
    return makePrefs(DEFAULT_MODE)
  }
}

function persist(prefs: UiPrefs): void {
  // Persist BOTH keys so an older bundle (mid-deploy) still reads a sane
  // value, and a newer bundle reads `mode`.
  try { localStorage.setItem(PREF_KEY, JSON.stringify({ mode: prefs.mode, simpleMode: prefs.simpleMode })) } catch { /* ignore quota / private-mode */ }
}

function notify() {
  _subscribers.forEach((cb) => { try { cb() } catch { /* ignore */ } })
}

export const uiPrefStore = {
  subscribe: (cb: () => void) => {
    _subscribers.add(cb)
    return () => { _subscribers.delete(cb) }
  },
  /** Stable-ref snapshot for useSyncExternalStore (see header note). */
  getSnapshot: (): UiPrefs => _cache,

  getMode: (): UiMode => _cache.mode,
  isSimpleMode: (): boolean => _cache.mode === 'simple',

  setMode: (mode: UiMode): void => {
    if (mode === _cache.mode) return
    _cache = makePrefs(mode)
    persist(_cache)
    notify()
  },
  toggleMode: (): void => {
    _cache = makePrefs(_cache.mode === 'simple' ? 'pro' : 'simple')
    persist(_cache)
    notify()
  },

  /** Backward-compat setter (ProfilePage Switch). */
  setSimpleMode: (enabled: boolean): void => {
    uiPrefStore.setMode(enabled ? 'simple' : 'pro')
  },

  __resetForTests: (): void => {
    _cache = makePrefs(DEFAULT_MODE)
    _subscribers.clear()
  },
}
