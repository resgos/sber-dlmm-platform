import { describe, it, expect, beforeEach } from 'vitest'
import { twoFactorStore, buildOtpauthUri } from '../store/twoFactorStore'

describe('twoFactorStore (G-20)', () => {
  beforeEach(() => {
    twoFactorStore.__resetForTests()
  })

  it('starts disabled by default', () => {
    const s = twoFactorStore.get()
    expect(s.enabled).toBe(false)
    expect(s.secret).toBeNull()
    expect(s.recoveryCodes).toHaveLength(0)
  })

  it('beginSetup returns a secret + 10 recovery codes WITHOUT enabling', () => {
    const { secret, recoveryCodes } = twoFactorStore.beginSetup()
    expect(secret).toMatch(/^[A-Z2-7]{32}$/)
    expect(recoveryCodes).toHaveLength(10)
    expect(twoFactorStore.get().enabled).toBe(false) // still off
  })

  it('enable: accepts valid 6-digit code + flips enabled state', () => {
    const { secret, recoveryCodes } = twoFactorStore.beginSetup()
    const ok = twoFactorStore.enable(secret, recoveryCodes, '123456')
    expect(ok).toBe(true)
    const s = twoFactorStore.get()
    expect(s.enabled).toBe(true)
    expect(s.secret).toBe(secret)
    expect(s.recoveryCodes).toEqual(recoveryCodes)
    expect(s.enabledAt).toMatch(/^\d{4}-\d{2}-\d{2}T/)
  })

  it('enable: rejects non-6-digit input', () => {
    const { secret, recoveryCodes } = twoFactorStore.beginSetup()
    expect(twoFactorStore.enable(secret, recoveryCodes, '12345')).toBe(false)
    expect(twoFactorStore.enable(secret, recoveryCodes, '1234567')).toBe(false)
    expect(twoFactorStore.enable(secret, recoveryCodes, 'abcdef')).toBe(false)
    expect(twoFactorStore.get().enabled).toBe(false)
  })

  it('disable wipes the state', () => {
    const { secret, recoveryCodes } = twoFactorStore.beginSetup()
    twoFactorStore.enable(secret, recoveryCodes, '123456')
    twoFactorStore.disable()
    expect(twoFactorStore.get().enabled).toBe(false)
    expect(twoFactorStore.get().secret).toBeNull()
  })

  it('useRecoveryCode: valid unused code → true + counter increments', () => {
    const { secret, recoveryCodes } = twoFactorStore.beginSetup()
    twoFactorStore.enable(secret, recoveryCodes, '123456')
    const first = recoveryCodes[0]
    expect(twoFactorStore.useRecoveryCode(first)).toBe(true)
    expect(twoFactorStore.get().recoveryCodesUsed).toBe(1)
  })

  it('useRecoveryCode: unknown code → false', () => {
    const { secret, recoveryCodes } = twoFactorStore.beginSetup()
    twoFactorStore.enable(secret, recoveryCodes, '123456')
    expect(twoFactorStore.useRecoveryCode('ZZZZ-ZZZZ')).toBe(false)
    expect(twoFactorStore.get().recoveryCodesUsed).toBe(0)
  })

  it('regenerateRecoveryCodes wipes the old set + returns 10 fresh ones', () => {
    const { secret, recoveryCodes: orig } = twoFactorStore.beginSetup()
    twoFactorStore.enable(secret, orig, '123456')
    twoFactorStore.useRecoveryCode(orig[0])
    expect(twoFactorStore.get().recoveryCodesUsed).toBe(1)

    const fresh = twoFactorStore.regenerateRecoveryCodes()
    expect(fresh).toHaveLength(10)
    expect(twoFactorStore.get().recoveryCodes).toEqual(fresh)
    expect(twoFactorStore.get().recoveryCodesUsed).toBe(0) // counter reset
    // Old codes don't work any more.
    expect(twoFactorStore.useRecoveryCode(orig[1])).toBe(false)
  })

  it('subscribe fires on state change', () => {
    let n = 0
    const unsub = twoFactorStore.subscribe(() => { n++ })
    const { secret, recoveryCodes } = twoFactorStore.beginSetup()
    // beginSetup doesn't notify (it doesn't persist) — only enable does
    twoFactorStore.enable(secret, recoveryCodes, '111111')
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
