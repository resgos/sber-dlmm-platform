// Sprint 10 (new feature) — Position alerts.
//
// Lets the user attach simple rules to a position and get a browser
// notification when the rule fires. Pure-frontend MVP: persistence
// in localStorage, polling via the existing React Query stale-time
// (30s), browser Notification API for the user-facing alert.
//
// Why not a backend service for v1: a full backend (alerts table +
// scheduled checker + Kafka producer + notification persistence) is
// 4-5 days of work and gates an inter-team contract. The browser-side
// version ships today, validates the UX, and informs the design of
// the eventual backend service (Sprint 11).
//
// Migration path (Sprint 11):
//   1. POST /api/v1/positions/{id}/alerts → upsert alert row.
//   2. New @Scheduled checker reads alerts every 60s.
//   3. Fires existing POSITION_OUT_OF_RANGE / FEES_THRESHOLD
//      notifications via dlmm-notification-service.
//   4. Frontend swap: localStorage reads → API reads. Rule shape
//      stays identical so component code doesn't change.

const STORAGE_KEY = 'dlmm.user.positionAlerts'

export type AlertType =
  | 'OUT_OF_RANGE'      // fires when current active bin leaves [binMin, binMax]
  | 'FEES_THRESHOLD'    // fires when unclaimed fees exceed threshold (in ₽-equiv)
  | 'VALUE_DROP'        // fires when position value drops below initial − threshold%

export interface PositionAlert {
  /** Stable UUID. */
  id: string
  /** Position this alert tracks. Position deletion implicitly removes alerts. */
  positionId: string
  type: AlertType
  /** Type-dependent: %-drop for VALUE_DROP, ₽-amount for FEES_THRESHOLD,
   *  unused (0) for OUT_OF_RANGE. */
  threshold: number
  /** Free-form note shown in the UI ("My SBER vault watch"). */
  label: string
  /** Active = checker evaluates. Toggle off without deleting to preserve
   *  the rule while suppressing notifications. */
  active: boolean
  /** ISO timestamp of the last successful fire — used for cooldown
   *  (don't re-notify within 5 minutes of the previous fire for the
   *  same rule). */
  lastFiredAt: string | null
  /** ISO timestamp when the rule was created. */
  createdAt: string
}

// UI-CRITIQUE 2026-05-22 fix — cached parsed snapshot. Без кэша
// каждый `useSyncExternalStore(... , list, ...)` getSnapshot
// возвращал бы новую ссылку (JSON.parse → new Array) на каждый
// рендер → React видит "data changed" → бесконечный цикл рендера
// → Minified React error #185.
//
// Cache invariant: cache === current data. Mutations go через
// safeWrite which обновляет cache одновременно с localStorage.
let cache: PositionAlert[] | null = null

function safeRead(): PositionAlert[] {
  if (cache !== null) return cache
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      cache = []
      return cache
    }
    const parsed = JSON.parse(raw)
    cache = Array.isArray(parsed) ? parsed : []
    return cache
  } catch {
    cache = []
    return cache
  }
}

function safeWrite(alerts: PositionAlert[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(alerts))
  } catch {
    /* ignore quota / private-mode */
  }
  // Update the cache with the new immutable array — subscribers
  // получают новую ссылку на следующем getSnapshot tick.
  cache = alerts
}

const listeners = new Set<() => void>()
function notify(): void {
  listeners.forEach((l) => {
    try { l() } catch { /* ignore */ }
  })
}

export const positionAlertsStore = {
  list(): PositionAlert[] {
    return safeRead()
  },

  forPosition(positionId: string): PositionAlert[] {
    return safeRead().filter((a) => a.positionId === positionId)
  },

  add(rule: Omit<PositionAlert, 'id' | 'lastFiredAt' | 'createdAt' | 'active'> & { active?: boolean }): PositionAlert {
    const newAlert: PositionAlert = {
      ...rule,
      active: rule.active ?? true,
      id: crypto.randomUUID(),
      lastFiredAt: null,
      createdAt: new Date().toISOString(),
    }
    safeWrite([...safeRead(), newAlert])
    notify()
    return newAlert
  },

  remove(id: string): void {
    safeWrite(safeRead().filter((a) => a.id !== id))
    notify()
  },

  toggle(id: string): void {
    safeWrite(safeRead().map((a) => a.id === id ? { ...a, active: !a.active } : a))
    notify()
  },

  /** Mark a rule as just-fired (cooldown bookkeeping). */
  recordFired(id: string): void {
    safeWrite(safeRead().map((a) =>
      a.id === id ? { ...a, lastFiredAt: new Date().toISOString() } : a,
    ))
    notify()
  },

  subscribe(listener: () => void): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },

  /**
   * Test-only reset. Localstorage clear in the test beforeEach doesn't
   * reach our module-level snapshot cache (added 72df39d for the
   * useSyncExternalStore stable-reference fix). Without this, the
   * previous test's alerts leak through into the next test's read.
   */
  __resetForTests(): void {
    try { localStorage.removeItem(STORAGE_KEY) } catch { /* ignore */ }
    cache = null
    notify()
  },
}

// Cooldown: 5 minutes between fires of the same rule. Prevents a
// flapping condition (e.g. active bin oscillating across boundary)
// from spamming.
export const COOLDOWN_MS = 5 * 60 * 1000

export function isOnCooldown(alert: PositionAlert): boolean {
  if (!alert.lastFiredAt) return false
  return (Date.now() - new Date(alert.lastFiredAt).getTime()) < COOLDOWN_MS
}

// ---------------------------------------------------------------------
// Sprint 10 wave 3 — alert history log.
//
// Records every fire across all rules so the user has a single
// "what happened" view in the drawer, not just per-rule lastFiredAt.
// Capped at HISTORY_MAX in-memory entries (refresh wipes — matches
// the auto-claim store pattern). Backend swap-in (Sprint 11) reads
// from notification-service's notification log instead.
// ---------------------------------------------------------------------

export interface AlertHistoryEntry {
  alertId: string
  alertLabel: string
  alertType: AlertType
  message: string
  delivery: 'browser' | 'in-app'
  firedAt: string
}

const ALERT_HISTORY_MAX = 50
// UI-CRITIQUE 2026-05-22 fix — historyCache replaces mutated-in-place
// `alertHistory: AlertHistoryEntry[]`. Mutating the same array in
// `record()` left useSyncExternalStore's reference unchanged, so
// React never re-rendered subscribers (the drawer history tab). Now
// every write replaces the reference; subscribers get a fresh
// snapshot on the next getSnapshot tick.
let historyCache: AlertHistoryEntry[] = []
const historyListeners = new Set<() => void>()

function notifyHistory(): void {
  historyListeners.forEach((l) => {
    try { l() } catch { /* ignore */ }
  })
}

export const alertHistoryStore = {
  list(): ReadonlyArray<AlertHistoryEntry> {
    return historyCache
  },
  record(entry: AlertHistoryEntry): void {
    historyCache = [entry, ...historyCache].slice(0, ALERT_HISTORY_MAX)
    notifyHistory()
  },
  clear(): void {
    historyCache = []
    notifyHistory()
  },
  subscribe(listener: () => void): () => void {
    historyListeners.add(listener)
    return () => historyListeners.delete(listener)
  },
}
