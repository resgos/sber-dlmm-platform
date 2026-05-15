// Persistent auth store backed by localStorage. Survives F5 / iframe reloads.
const TOKEN_KEY = 'dlmm.admin.token'
const USER_KEY = 'dlmm.admin.user'

export interface StoredUser {
  userId: string
  email: string
  role: string
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
    try { localStorage.setItem(TOKEN_KEY, token) } catch { /* ignore */ }
  },

  removeToken: (): void => {
    try { localStorage.removeItem(TOKEN_KEY) } catch { /* ignore */ }
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
      localStorage.removeItem(USER_KEY)
    } catch { /* ignore */ }
  },
}
