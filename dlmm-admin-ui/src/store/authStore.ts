// In-memory auth store (compatible with sandboxed environments)
let _token: string | null = null
let _user: StoredUser | null = null

export interface StoredUser {
  userId: string
  email: string
  role: string
}

// Auto-initialize with demo admin user
_token = 'demo-admin-token'
_user = {
  userId: '1',
  email: 'admin@sber-dlmm.ru',
  role: 'SUPER_ADMIN',
}

export const authStore = {
  getToken: (): string | null => {
    return _token
  },

  setToken: (token: string): void => {
    _token = token
  },

  removeToken: (): void => {
    _token = null
  },

  getUser: (): StoredUser | null => {
    return _user
  },

  setUser: (user: StoredUser): void => {
    _user = user
  },

  removeUser: (): void => {
    _user = null
  },

  isAuthenticated: (): boolean => {
    return !!_token
  },

  logout: (): void => {
    _token = null
    _user = null
  },
}
