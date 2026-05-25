import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the apiClient module BEFORE importing the store so the store's
// imported `apiClient` reference points at the mock.
vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

import { twoFactorStore, buildOtpauthUri } from '../store/twoFactorStore'
import apiClient from '@/api/client'

const mockedApi = apiClient as unknown as {
  get: ReturnType<typeof vi.fn>
  post: ReturnType<typeof vi.fn>
}

/** Flush queued microtasks (pending Promise then/catch handlers).
 *  Five cycles covers the deepest then/catch chain in the store. */
async function flushPromises(): Promise<void> {
  for (let i = 0; i < 5; i++) await Promise.resolve()
}

/**
 * Sprint 11 G-20 — twoFactorStore tests (backend swap-in).
 *
 * <p>Before: the store was a localStorage stub that accepted any 6-digit
 * code. Tests were synchronous. After: the store proxies to backend
 * endpoints (POST /users/me/2fa/{begin,enable,verify,disable}, GET
 * /status). Sync API surface is preserved for backward compat with
 * TwoFactorSettings.tsx (which is out of scope for this PR), so the
 * tests stay mostly synchronous and use flushPromises() to wait for
 * the fire-and-forget backend calls' then/catch handlers.
 */
describe('twoFactorStore (G-20 backend-backed)', () => {
  const VALID_SECRET = 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP'
  const RECOVERY_CODES = Array.from({ length: 10 }, (_, i) => `AAAA-${String(i).padStart(4, '0')}`)

  beforeEach(() => {
    twoFactorStore.__resetForTests()
    mockedApi.get.mockReset()
    mockedApi.post.mockReset()
  })

  it('starts disabled by default (before any loadStatus call)', () => {
    const s = twoFactorStore.get()
    expect(s.enabled).toBe(false)
    expect(s.secret).toBeNull()
    expect(s.recoveryCodes).toHaveLength(0)
  })

  it('beginSetup returns placeholder values immediately, fetches real ones in background', async () => {
    mockedApi.post.mockResolvedValueOnce({
      data: {
        secret: VALID_SECRET,
        otpauthUri: `otpauth://totp/Sber%20DLMM:demo?secret=${VALID_SECRET}`,
        recoveryCodes: RECOVERY_CODES,
      },
    })
    const sync = twoFactorStore.beginSetup()
    expect(sync.secret).toMatch(/PENDING/)
    expect(sync.recoveryCodes).toHaveLength(10)
    expect(twoFactorStore.get().enabled).toBe(false)

    await flushPromises()
    const s = twoFactorStore.get()
    expect(s.secret).toBe(VALID_SECRET)
    expect(s.recoveryCodes).toHaveLength(10)
    expect(mockedApi.post).toHaveBeenCalledWith('/users/me/2fa/begin')
  })

  it('enable: optimistically flips state on 6-digit input, fires backend POST', async () => {
    mockedApi.post.mockResolvedValueOnce({ data: {} })
    const ok = twoFactorStore.enable(VALID_SECRET, RECOVERY_CODES, '123456')
    expect(ok).toBe(true)
    const s = twoFactorStore.get()
    expect(s.enabled).toBe(true)
    expect(s.recoveryCodes).toEqual(RECOVERY_CODES)
    expect(s.enabledAt).toMatch(/^\d{4}-\d{2}-\d{2}T/)
    await flushPromises()
    expect(mockedApi.post).toHaveBeenCalledWith('/users/me/2fa/enable', {
      secret: VALID_SECRET,
      recoveryCodes: RECOVERY_CODES,
      code: '123456',
    })
  })

  it('enable: reverts optimistic state if backend returns 400', async () => {
    mockedApi.post.mockRejectedValueOnce({ response: { status: 400 } })
    const ok = twoFactorStore.enable(VALID_SECRET, RECOVERY_CODES, '000000')
    expect(ok).toBe(true) // optimistic — same shape as the old stub
    await flushPromises()
    const s = twoFactorStore.get()
    expect(s.enabled).toBe(false)
    expect(s.secret).toBeNull()
  })

  it('enable: rejects non-6-digit input without firing any HTTP call', () => {
    expect(twoFactorStore.enable(VALID_SECRET, RECOVERY_CODES, '12345')).toBe(false)
    expect(twoFactorStore.enable(VALID_SECRET, RECOVERY_CODES, '1234567')).toBe(false)
    expect(twoFactorStore.enable(VALID_SECRET, RECOVERY_CODES, 'abcdef')).toBe(false)
    expect(twoFactorStore.get().enabled).toBe(false)
    expect(mockedApi.post).not.toHaveBeenCalled()
  })

  it('enable: refuses to fire backend when secret is still the PENDING placeholder', () => {
    // User clicked "verify" before /begin completed. Backend would
    // reject — refuse client-side to save a round trip.
    const ok = twoFactorStore.enable('PENDINGPENDINGPENDINGPENDINGPEND', RECOVERY_CODES, '123456')
    expect(ok).toBe(false)
    expect(mockedApi.post).not.toHaveBeenCalled()
  })

  it('disable: wipes state synchronously and fires backend POST', async () => {
    mockedApi.post.mockResolvedValueOnce({ data: {} }) // /enable
    mockedApi.post.mockResolvedValueOnce({ data: {} }) // /disable
    twoFactorStore.enable(VALID_SECRET, RECOVERY_CODES, '123456')
    twoFactorStore.disable()
    expect(twoFactorStore.get().enabled).toBe(false)
    expect(twoFactorStore.get().secret).toBeNull()
    await flushPromises()
    expect(mockedApi.post).toHaveBeenCalledWith('/users/me/2fa/disable')
  })

  it('useRecoveryCode: increments used counter on plausible input, fires verify with normalised code', async () => {
    mockedApi.post.mockResolvedValueOnce({ data: {} }) // /enable
    mockedApi.post.mockResolvedValueOnce({ data: {} }) // /verify
    twoFactorStore.enable(VALID_SECRET, RECOVERY_CODES, '123456')
    const ok = twoFactorStore.useRecoveryCode(RECOVERY_CODES[0])
    expect(ok).toBe(true)
    expect(twoFactorStore.get().recoveryCodesUsed).toBe(1)
    await flushPromises()
    // Store strips dashes before POSTing (backend normalises the same way).
    expect(mockedApi.post).toHaveBeenCalledWith('/users/me/2fa/verify', {
      code: RECOVERY_CODES[0].replace(/-/g, ''),
    })
  })

  it('useRecoveryCode: reverts optimistic counter on backend 401', async () => {
    mockedApi.post.mockResolvedValueOnce({ data: {} }) // /enable
    mockedApi.post.mockRejectedValueOnce({ response: { status: 401 } }) // /verify rejected
    twoFactorStore.enable(VALID_SECRET, RECOVERY_CODES, '123456')
    const ok = twoFactorStore.useRecoveryCode('BADCODE')
    expect(ok).toBe(true) // optimistic increment
    expect(twoFactorStore.get().recoveryCodesUsed).toBe(1)
    await flushPromises()
    expect(twoFactorStore.get().recoveryCodesUsed).toBe(0)
  })

  it('regenerateRecoveryCodes is a no-op (now requires re-enrolment via wizard)', () => {
    const result = twoFactorStore.regenerateRecoveryCodes()
    expect(result).toEqual([])
    expect(mockedApi.post).not.toHaveBeenCalled()
  })

  it('loadStatus: maps server response to state shape', async () => {
    mockedApi.get.mockResolvedValueOnce({
      data: { enabled: true, enabledAt: '2026-05-25T10:00:00Z', recoveryCodesRemaining: 7 },
    })
    const result = await twoFactorStore.loadStatus()
    expect(result.enabled).toBe(true)
    expect(result.enabledAt).toBe('2026-05-25T10:00:00Z')
    // Total inferred as DEFAULT_RECOVERY_CODE_COUNT (10) on fresh load.
    expect(result.recoveryCodes).toHaveLength(10)
    expect(result.recoveryCodesUsed).toBe(3) // 10 - 7
    expect(mockedApi.get).toHaveBeenCalledWith('/users/me/2fa/status')
  })

  it('loadStatus: network failure keeps cache as-is (no flash to disabled)', async () => {
    mockedApi.post.mockResolvedValueOnce({ data: {} })
    twoFactorStore.enable(VALID_SECRET, RECOVERY_CODES, '123456')
    expect(twoFactorStore.get().enabled).toBe(true)
    mockedApi.get.mockRejectedValueOnce(new Error('network'))
    const result = await twoFactorStore.loadStatus()
    expect(result.enabled).toBe(true)
  })

  it('subscribe fires on state change', () => {
    let n = 0
    const unsub = twoFactorStore.subscribe(() => { n++ })
    mockedApi.post.mockResolvedValueOnce({ data: {} })
    twoFactorStore.enable(VALID_SECRET, RECOVERY_CODES, '111111')
    expect(n).toBeGreaterThan(0)
    unsub()
  })

  it('buildOtpauthUri produces an authenticator-app-compatible URI', () => {
    const uri = buildOtpauthUri('demo@sber.ru', 'JBSWY3DPEHPK3PXP')
    expect(uri).toMatch(/^otpauth:\/\/totp\//)
    expect(uri).toContain('secret=JBSWY3DPEHPK3PXP')
    expect(uri).toContain('issuer=')
    expect(uri).toContain('algorithm=SHA1')
    expect(uri).toContain('digits=6')
    expect(uri).toContain('period=30')
  })
})
