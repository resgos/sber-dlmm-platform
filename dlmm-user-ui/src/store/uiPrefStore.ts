// Simple-mode flag: when enabled, hides advanced sidebar items
// (Ребаланс, Команда, Автозабор) for users who want a cleaner UX.
// Persisted to localStorage. Follows the module-level cache pattern
// (stable reference for useSyncExternalStore).

const PREF_KEY = 'dlmm.user.uiPrefs'

interface UiPrefs {
  simpleMode: boolean
}

const DEFAULT: UiPrefs = { simpleMode: false }

let _cache: UiPrefs = readFromStorage()
const _subscribers = new Set<() => void>()

function readFromStorage(): UiPrefs {
  try {
    const raw = localStorage.getItem(PREF_KEY)
    if (!raw) return { ...DEFAULT }
    const parsed = JSON.parse(raw)
    if (typeof parsed?.simpleMode !== 'boolean') return { ...DEFAULT }
    return parsed as UiPrefs
  } catch {
    return { ...DEFAULT }
  }
}

function notify() {
  _subscribers.forEach((cb) => cb())
}

export const uiPrefStore = {
  subscribe: (cb: () => void) => {
    _subscribers.add(cb)
    return () => { _subscribers.delete(cb) }
  },
  getSnapshot: (): UiPrefs => _cache,
  isSimpleMode: (): boolean => _cache.simpleMode,
  setSimpleMode: (enabled: boolean): void => {
    _cache = { ..._cache, simpleMode: enabled }
    try { localStorage.setItem(PREF_KEY, JSON.stringify(_cache)) } catch {}
    notify()
  },
  __resetForTests: (): void => {
    _cache = { ...DEFAULT }
    _subscribers.clear()
  },
}
