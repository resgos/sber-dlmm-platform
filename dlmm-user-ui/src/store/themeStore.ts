// Sprint 9-DS-r4 P2-15 — dark-mode toggle.
//
// Persists the user's theme preference to localStorage and applies it
// to <html data-theme="..."> on every read. The CSS in sber-theme.css
// owns the actual colour overrides under the `[data-theme="dark"]`
// selector — this module is just the preference store + apply hook.
//
// Why localStorage and not authStore? Theme is a per-device preference,
// not a per-user one. A treasurer who uses the dashboard on two screens
// reasonably expects each to remember its own setting. The trade-off is
// that signing in on a new device starts in 'system' mode — which is
// fine because we pick up the OS preference there too.

const STORAGE_KEY = 'dlmm.user.theme'

export type ThemeMode = 'light' | 'dark' | 'system'

function safeRead(): ThemeMode {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw === 'light' || raw === 'dark' || raw === 'system') return raw
  } catch {
    // localStorage may be disabled in private-browse or quota-exceeded;
    // fall through to the default.
  }
  return 'system'
}

function resolveEffective(mode: ThemeMode): 'light' | 'dark' {
  if (mode === 'light' || mode === 'dark') return mode
  // 'system' — honour the OS preference. matchMedia is sync at first
  // load, so this is fine even before React mounts.
  if (typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches) {
    return 'dark'
  }
  return 'light'
}

function apply(mode: ThemeMode): void {
  const effective = resolveEffective(mode)
  if (typeof document !== 'undefined') {
    document.documentElement.setAttribute('data-theme', effective)
    // Also set a colour-scheme hint so browser-native UI (scrollbars,
    // form controls before Plasma overrides land) matches.
    document.documentElement.style.colorScheme = effective
  }
}

// Listener bookkeeping: we want multiple subscribers (e.g. ProfilePage
// toggle + a hypothetical settings dropdown in the header) to all
// re-render when the mode changes. Pure cross-tab sync via the
// `storage` event is bonus, not required.
const listeners = new Set<() => void>()

function notify(): void {
  listeners.forEach((l) => {
    try { l() } catch { /* ignore individual subscriber faults */ }
  })
}

export const themeStore = {
  /** Current preference (may be 'system'). */
  getMode(): ThemeMode {
    return safeRead()
  },

  /** Effective theme after resolving 'system' against the OS. */
  getEffective(): 'light' | 'dark' {
    return resolveEffective(safeRead())
  },

  /** Persist a new preference and re-apply to <html>. */
  setMode(mode: ThemeMode): void {
    try { localStorage.setItem(STORAGE_KEY, mode) } catch { /* ignore */ }
    apply(mode)
    notify()
  },

  /** React-friendly subscription used by the toggle component. */
  subscribe(listener: () => void): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },

  /** Idempotent — call once at app boot from main.tsx. */
  initialize(): void {
    apply(safeRead())

    // Track OS preference changes only when the user is in 'system'
    // mode; if they've explicitly picked light or dark we stay there.
    if (typeof window !== 'undefined' && window.matchMedia) {
      const mql = window.matchMedia('(prefers-color-scheme: dark)')
      const onChange = (): void => {
        if (safeRead() === 'system') {
          apply('system')
          notify()
        }
      }
      // addEventListener is supported in all modern browsers; the older
      // addListener is left out (deprecated since 2020).
      mql.addEventListener?.('change', onChange)
    }

    // Cross-tab sync: setting the theme in tab A should update tab B.
    if (typeof window !== 'undefined') {
      window.addEventListener('storage', (e) => {
        if (e.key === STORAGE_KEY) {
          apply(safeRead())
          notify()
        }
      })
    }
  },
}
