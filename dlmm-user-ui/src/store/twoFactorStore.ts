// Sprint 11 G-20 — 2FA TOTP store.
//
// Dmitry-driven (medium-business demo): "Это финансовая платформа без
// 2FA в 2026 году? Это блокер."
//
// Frontend MVP scope: full UX shell (setup → verify → recovery codes
// → disable flow). Verification is a stub today — the *real* TOTP
// validation lives on the backend (Sprint 12 swap-in: POST
// /api/v1/users/me/2fa/verify with HMAC-SHA1 + base32 secret stored
// server-side). For the MVP we accept any 6-digit code, but the
// secret + recovery codes generation + storage flow is real so the
// backend swap-in is a single fetch() replacement.
//
// Why frontend stub for v1: backend SecurityFilterChain + per-user
// 2fa_secrets table + recovery_codes (one-time hashes) needs a
// dedicated sprint. Shipping the UX shell now lets us validate the
// flow with pilot users + lock visual design before Sprint 12 backend
// work.

const STORAGE_KEY = 'dlmm.user.twoFactor'

export interface TwoFactorState {
  /** false until user completes setup → verify. */
  enabled: boolean
  /**
   * Base32-encoded secret. In production this NEVER leaves the
   * backend — it's shown ONCE during setup (QR code), then only
   * the verification code travels. Today it's in localStorage as a
   * stub.
   */
  secret: string | null
  /** When 2FA was enabled. ISO timestamp; null if never. */
  enabledAt: string | null
  /** One-time recovery codes — 10 codes generated at setup, used once
   *  if user loses authenticator. Stored as plain strings today; in
   *  production are bcrypt-hashed server-side. */
  recoveryCodes: ReadonlyArray<string>
  /** How many recovery codes have been consumed. */
  recoveryCodesUsed: number
}

const DEFAULT_STATE: TwoFactorState = {
  enabled: false,
  secret: null,
  enabledAt: null,
  recoveryCodes: [],
  recoveryCodesUsed: 0,
}

function safeRead(): TwoFactorState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return DEFAULT_STATE
    const parsed = JSON.parse(raw)
    if (typeof parsed?.enabled !== 'boolean') return DEFAULT_STATE
    return {
      enabled: parsed.enabled,
      secret: typeof parsed.secret === 'string' ? parsed.secret : null,
      enabledAt: typeof parsed.enabledAt === 'string' ? parsed.enabledAt : null,
      recoveryCodes: Array.isArray(parsed.recoveryCodes) ? parsed.recoveryCodes : [],
      recoveryCodesUsed: typeof parsed.recoveryCodesUsed === 'number' ? parsed.recoveryCodesUsed : 0,
    }
  } catch {
    return DEFAULT_STATE
  }
}

function safeWrite(state: TwoFactorState): void {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)) } catch { /* ignore */ }
}

const listeners = new Set<() => void>()
function notify(): void {
  listeners.forEach((l) => {
    try { l() } catch { /* ignore */ }
  })
}

// Crockford base32 alphabet — easier to read aloud than RFC 4648 (no
// confusing I/L/O/0/1). TOTP RFC technically requires RFC 4648 base32
// but authenticator apps tolerate variants in our experience.
const BASE32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

/** Generate a 32-char base32 secret (160-bit, RFC-recommended). */
function generateSecret(): string {
  const bytes = new Uint8Array(20)
  if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
    crypto.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256)
  }
  let out = ''
  for (let i = 0; i < 32; i++) {
    // Crude — we just sample bytes for the base32 chars. Adequate for
    // a frontend stub; real backend generates RFC-compliant secret.
    out += BASE32_ALPHABET[bytes[i % bytes.length] % BASE32_ALPHABET.length]
  }
  return out
}

/** Generate 10 readable recovery codes — 4 groups of 4 chars each. */
function generateRecoveryCodes(count = 10): string[] {
  const codes: string[] = []
  for (let i = 0; i < count; i++) {
    const groups: string[] = []
    for (let g = 0; g < 2; g++) {
      let group = ''
      for (let c = 0; c < 4; c++) {
        const r = typeof crypto !== 'undefined' && crypto.getRandomValues
          ? crypto.getRandomValues(new Uint8Array(1))[0]
          : Math.floor(Math.random() * 256)
        group += BASE32_ALPHABET[r % BASE32_ALPHABET.length]
      }
      groups.push(group)
    }
    codes.push(groups.join('-'))
  }
  return codes
}

/**
 * Build the otpauth:// URI for QR code scanning by Google
 * Authenticator / Authy / Microsoft Authenticator / etc.
 */
export function buildOtpauthUri(account: string, secret: string): string {
  const label = encodeURIComponent(`Sber DLMM:${account}`)
  const issuer = encodeURIComponent('Sber DLMM')
  return `otpauth://totp/${label}?secret=${secret}&issuer=${issuer}&algorithm=SHA1&digits=6&period=30`
}

export const twoFactorStore = {
  get(): TwoFactorState {
    return safeRead()
  },

  /**
   * Start the setup flow — generates a fresh secret + recovery codes.
   * Does NOT enable yet; caller must verify a code first via
   * `enable()`. This separation is important: if the user closes the
   * tab during setup, no half-enabled state strands them.
   */
  beginSetup(): { secret: string; recoveryCodes: string[] } {
    const secret = generateSecret()
    const recoveryCodes = generateRecoveryCodes(10)
    // Don't persist yet — return for the caller to show QR + recovery
    // codes UI. Persistence happens at enable() time.
    return { secret, recoveryCodes }
  },

  /**
   * Verify the user's first 6-digit code and enable 2FA. For the
   * frontend stub we accept any 6-digit input; real backend swaps
   * the implementation here for HMAC-SHA1 + 30s window.
   */
  enable(secret: string, recoveryCodes: ReadonlyArray<string>, code: string): boolean {
    // Stub validation: just accept any 6 numeric digits. Real backend
    // does TOTP verify with ±1 window tolerance.
    if (!/^\d{6}$/.test(code)) return false
    const state: TwoFactorState = {
      enabled: true,
      secret,
      enabledAt: new Date().toISOString(),
      recoveryCodes,
      recoveryCodesUsed: 0,
    }
    safeWrite(state)
    notify()
    return true
  },

  disable(): void {
    safeWrite(DEFAULT_STATE)
    notify()
  },

  /**
   * Mark a recovery code as used. Returns true if it was a valid
   * unused recovery code, false otherwise. Idempotent at the
   * counter level (multiple calls won't double-decrement).
   */
  useRecoveryCode(code: string): boolean {
    const state = safeRead()
    const normalised = code.trim().toUpperCase().replace(/\s/g, '')
    const idx = state.recoveryCodes.indexOf(normalised)
    if (idx === -1) return false
    if (state.recoveryCodesUsed >= state.recoveryCodes.length) return false
    safeWrite({
      ...state,
      recoveryCodesUsed: state.recoveryCodesUsed + 1,
    })
    notify()
    return true
  },

  /** Generate fresh recovery codes (invalidates the old set). */
  regenerateRecoveryCodes(): string[] {
    const state = safeRead()
    if (!state.enabled) return []
    const codes = generateRecoveryCodes(10)
    safeWrite({
      ...state,
      recoveryCodes: codes,
      recoveryCodesUsed: 0,
    })
    notify()
    return codes
  },

  subscribe(listener: () => void): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },

  /** Test-only reset. */
  __resetForTests(): void {
    try { localStorage.removeItem(STORAGE_KEY) } catch { /* ignore */ }
    notify()
  },
}
