import { describe, it, expect, beforeEach } from 'vitest'
import { authStore } from '../store/authStore'

describe('authStore', () => {
  beforeEach(() => {
    authStore.logout()
  })

  it('starts unauthenticated', () => {
    expect(authStore.isAuthenticated()).toBe(false)
    expect(authStore.getToken()).toBeNull()
    expect(authStore.getUser()).toBeNull()
  })

  it('setToken makes store authenticated', () => {
    authStore.setToken('test-token-123')
    expect(authStore.isAuthenticated()).toBe(true)
    expect(authStore.getToken()).toBe('test-token-123')
  })

  it('setUser stores user data with kycStatus', () => {
    const user = {
      userId: 'u-001',
      email: 'test@test.com',
      role: 'USER',
      kycStatus: 'VERIFIED',
    }
    authStore.setUser(user)
    expect(authStore.getUser()).toEqual(user)
    expect(authStore.getUser()?.kycStatus).toBe('VERIFIED')
  })

  it('logout clears token and user', () => {
    authStore.setToken('token')
    authStore.setUser({ userId: '1', email: 'a@b.com', role: 'USER', kycStatus: 'PENDING' })

    authStore.logout()

    expect(authStore.isAuthenticated()).toBe(false)
    expect(authStore.getToken()).toBeNull()
    expect(authStore.getUser()).toBeNull()
  })

  it('removeToken only clears token, not user', () => {
    authStore.setToken('token')
    authStore.setUser({ userId: '1', email: 'a@b.com', role: 'USER', kycStatus: 'VERIFIED' })

    authStore.removeToken()

    expect(authStore.isAuthenticated()).toBe(false)
    expect(authStore.getUser()).not.toBeNull()
  })

  it('removeUser only clears user, not token', () => {
    authStore.setToken('token')
    authStore.setUser({ userId: '1', email: 'a@b.com', role: 'USER', kycStatus: 'VERIFIED' })

    authStore.removeUser()

    expect(authStore.isAuthenticated()).toBe(true)
    expect(authStore.getUser()).toBeNull()
  })
})
