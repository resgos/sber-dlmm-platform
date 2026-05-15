// Auth state is persisted to localStorage so login survives a hard reload
// and JWT in URL/iframe scenarios. Previously the store was in-memory only,
// which made every F5 kick the user back to /login mid-session.
const TOKEN_KEY = 'dlmm.auth.token'
const REFRESH_KEY = 'dlmm.auth.refresh'
const USER_KEY = 'dlmm.auth.user'

export interface StoredUser {
  userId: string
  email: string
  role: string
  kycStatus: string
}

function safeRead<T>(key: string, parse: (raw: string) => T): T | null {
  try {
    const raw = localStorage.getItem(key)
    return raw ? parse(raw) : null
  } catch {
    return null
  }
}

export const authStore = {
  getToken: (): string | null => safeRead(TOKEN_KEY, (s) => s),

  setToken: (token: string): void => {
    try { localStorage.setItem(TOKEN_KEY, token) } catch { /* ignore quota / private mode */ }
  },

  removeToken: (): void => {
    try { localStorage.removeItem(TOKEN_KEY) } catch { /* ignore */ }
  },

  getRefreshToken: (): string | null => safeRead(REFRESH_KEY, (s) => s),

  setRefreshToken: (token: string): void => {
    try { localStorage.setItem(REFRESH_KEY, token) } catch { /* ignore */ }
  },

  getUser: (): StoredUser | null => safeRead(USER_KEY, (s) => JSON.parse(s) as StoredUser),

  setUser: (user: StoredUser): void => {
    try { localStorage.setItem(USER_KEY, JSON.stringify(user)) } catch { /* ignore */ }
  },

  removeUser: (): void => {
    try { localStorage.removeItem(USER_KEY) } catch { /* ignore */ }
  },

  isAuthenticated: (): boolean => !!safeRead(TOKEN_KEY, (s) => s),

  logout: (): void => {
    try {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(REFRESH_KEY)
      localStorage.removeItem(USER_KEY)
    } catch { /* ignore */ }
  },
}
