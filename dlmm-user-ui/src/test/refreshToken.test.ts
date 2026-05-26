import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { AxiosError, AxiosResponse } from 'axios'

/**
 * R-04 — automatic JWT refresh on 401/403.
 *
 * The interceptor uses a bare `axios.post(...)` (NOT `apiClient`) for the
 * refresh call so it can't recurse on its own /auth/refresh failure.
 *
 * To test the interceptor in isolation we:
 *   1. Mock the default `axios.post` BEFORE apiClient imports it, so the
 *      refresh call lands on the mock and never reaches jsdom XHR.
 *   2. Stub `apiClient.request` so the retry of the original request
 *      doesn't fire a real XHR either (jsdom XHR errors with
 *      AggregateError in worker mode → DataCloneError on rejection).
 *   3. Invoke the response interceptor's rejected handler directly with
 *      a synthesised AxiosError — exactly the path a real 401 takes.
 */

// CRITICAL: this hoists to top of file (vitest auto-hoists vi.mock).
// Must run before `import apiClient from '@/api/client'` below.
vi.mock('axios', async () => {
  const actual = await vi.importActual<typeof import('axios')>('axios')
  // Wrap the default export so apiClient still gets a real `axios.create`
  // (for its instance + interceptors) but `axios.post` is a vi.fn() we
  // can control. The interceptor calls `axios.post(...)` (the bare
  // default), which we intercept here.
  const mockPost = vi.fn()
  // Re-export everything actual exports, swap `default.post`.
  const newDefault = new Proxy(actual.default, {
    get(target, prop, receiver) {
      if (prop === 'post') return mockPost
      return Reflect.get(target, prop, receiver)
    },
  })
  return {
    ...actual,
    default: newDefault,
    // Re-export the named bindings axios ships (some tools import `{ AxiosError }`).
    __mockPost: mockPost,
  }
})

import axios from 'axios'
import apiClient from '@/api/client'
import { authStore } from '@/store/authStore'

// Pluck the mock fn out so we can introspect it in each test.
const mockBareAxiosPost = (axios as unknown as { post: ReturnType<typeof vi.fn> }).post

describe('R-04 — apiClient JWT refresh-on-401', () => {
  // Typed loose to dodge the AxiosRequestConfig<unknown> generic that
  // ReturnType<typeof vi.spyOn> tries to infer; we only need .mock.calls
  // and .mockResolvedValue here.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let retryStub: any

  /** Invoke the response interceptor's rejected handler directly. */
  function fireInterceptorWith401(
    url = '/positions',
    method: 'get' | 'post' = 'get',
    status = 401,
  ): Promise<unknown> {
    const config = {
      url,
      method,
      baseURL: '/api/v1',
      headers: { Authorization: 'Bearer expired-access-token' },
    }
    const err = new Error('Request failed') as AxiosError
    err.config = config as never
    err.response = {
      status,
      data: { message: 'Unauthorized' },
      headers: {},
      config: config as never,
      statusText: 'Unauthorized',
    }
    err.isAxiosError = true

    const handlers = (apiClient.interceptors.response as unknown as {
      handlers: Array<{ rejected?: (e: unknown) => unknown }>
    }).handlers
    const lastRejected = handlers[handlers.length - 1]?.rejected
    if (!lastRejected) throw new Error('No response interceptor installed')
    return Promise.resolve(lastRejected(err)) as Promise<unknown>
  }

  function makeRefreshResponse(access = 'new-access-token', refresh = 'new-refresh-token'): AxiosResponse {
    return {
      data: { accessToken: access, refreshToken: refresh, expiresIn: 1800,
              user: { id: 'u-1', email: 'a@b.com', firstName: 'A', lastName: 'B',
                      role: 'USER', kycStatus: 'VERIFIED' } },
      status: 200, statusText: 'OK', headers: {}, config: {} as never,
    }
  }

  beforeEach(() => {
    authStore.logout()
    authStore.setToken('expired-access-token')
    authStore.setRefreshToken('valid-refresh-token')
    mockBareAxiosPost.mockReset()
    mockBareAxiosPost.mockResolvedValue(makeRefreshResponse())
    retryStub = vi
      .spyOn(apiClient, 'request')
      .mockResolvedValue({ data: { ok: true }, status: 200 } as never)
    window.location.hash = ''
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('401 with valid refresh token: calls /auth/refresh, retries, stores new tokens', async () => {
    const result = (await fireInterceptorWith401()) as { data: { ok: boolean } }

    expect(mockBareAxiosPost).toHaveBeenCalledTimes(1)
    expect(mockBareAxiosPost).toHaveBeenCalledWith(
      '/api/v1/auth/refresh',
      { refreshToken: 'valid-refresh-token' },
      expect.objectContaining({ headers: { 'Content-Type': 'application/json' } }),
    )
    expect(authStore.getToken()).toBe('new-access-token')
    expect(authStore.getRefreshToken()).toBe('new-refresh-token')
    expect(retryStub).toHaveBeenCalledTimes(1)
    expect(result.data.ok).toBe(true)
    const retriedConfig = retryStub.mock.calls[0][0] as { headers: Record<string, string> }
    expect(retriedConfig.headers.Authorization).toBe('Bearer new-access-token')
  })

  it('403 (gateway expired-token signal) also triggers refresh', async () => {
    await fireInterceptorWith401('/positions', 'get', 403)
    expect(mockBareAxiosPost).toHaveBeenCalledTimes(1)
    expect(retryStub).toHaveBeenCalledTimes(1)
  })

  it('refresh failure → logout + redirect to /login, rejects original promise', async () => {
    mockBareAxiosPost.mockRejectedValueOnce(new Error('refresh token expired'))

    await expect(fireInterceptorWith401()).rejects.toThrow(/refresh token expired/)
    expect(authStore.getToken()).toBeNull()
    expect(authStore.getRefreshToken()).toBeNull()
    expect(window.location.hash).toBe('#/login')
    expect(retryStub).not.toHaveBeenCalled()
  })

  it('concurrent 401s share a single refresh call (no thundering herd)', async () => {
    let resolveRefresh: (v: AxiosResponse) => void
    mockBareAxiosPost.mockReturnValueOnce(
      new Promise<AxiosResponse>((res) => { resolveRefresh = res }),
    )

    const p1 = fireInterceptorWith401('/positions')
    const p2 = fireInterceptorWith401('/pools')
    await new Promise((r) => setTimeout(r, 0))

    resolveRefresh!(makeRefreshResponse('shared-new-token', 'shared-new-refresh'))
    await Promise.all([p1, p2])

    // CRITICAL: only one /auth/refresh call despite two 401s.
    expect(mockBareAxiosPost).toHaveBeenCalledTimes(1)
    expect(retryStub).toHaveBeenCalledTimes(2)
    expect(authStore.getToken()).toBe('shared-new-token')
    expect(authStore.getRefreshToken()).toBe('shared-new-refresh')
  })

  it('after a refresh completes, a LATER 401 starts a fresh refresh', async () => {
    await fireInterceptorWith401('/positions')
    expect(mockBareAxiosPost).toHaveBeenCalledTimes(1)

    authStore.setToken('expired-access-token-v2')
    authStore.setRefreshToken('valid-refresh-token-v2')

    await fireInterceptorWith401('/swap')
    expect(mockBareAxiosPost).toHaveBeenCalledTimes(2)
    expect(retryStub).toHaveBeenCalledTimes(2)
  })

  it('401 on /auth/login does NOT trigger refresh (bad password is not auth-expiry)', async () => {
    await expect(fireInterceptorWith401('/auth/login', 'post')).rejects.toBeDefined()

    expect(mockBareAxiosPost).not.toHaveBeenCalled()
    expect(retryStub).not.toHaveBeenCalled()
    expect(authStore.getToken()).toBe('expired-access-token')
  })

  it('401 on /auth/refresh itself does NOT recurse (would be infinite loop)', async () => {
    await expect(fireInterceptorWith401('/auth/refresh', 'post')).rejects.toBeDefined()
    expect(mockBareAxiosPost).not.toHaveBeenCalled()
    expect(retryStub).not.toHaveBeenCalled()
  })

  it('401 on /auth/register does NOT trigger refresh', async () => {
    await expect(fireInterceptorWith401('/auth/register', 'post')).rejects.toBeDefined()
    expect(mockBareAxiosPost).not.toHaveBeenCalled()
    expect(retryStub).not.toHaveBeenCalled()
  })

  it('401 with NO refresh token in store → force logout', async () => {
    authStore.removeRefreshToken()

    await expect(fireInterceptorWith401()).rejects.toBeDefined()

    expect(mockBareAxiosPost).not.toHaveBeenCalled()
    expect(retryStub).not.toHaveBeenCalled()
    expect(authStore.getToken()).toBeNull()
    expect(window.location.hash).toBe('#/login')
  })

  it('401 with NO access token at all → propagate unchanged (task #21 carve-out)', async () => {
    authStore.removeToken()
    authStore.removeRefreshToken()
    const hashBefore = window.location.hash

    await expect(fireInterceptorWith401()).rejects.toBeDefined()

    expect(mockBareAxiosPost).not.toHaveBeenCalled()
    expect(retryStub).not.toHaveBeenCalled()
    expect(window.location.hash).toBe(hashBefore)
  })

  it('non-401 errors propagate unchanged (404/500 are not auth concerns)', async () => {
    await expect(fireInterceptorWith401('/positions', 'get', 404)).rejects.toBeDefined()
    await expect(fireInterceptorWith401('/positions', 'get', 500)).rejects.toBeDefined()

    expect(mockBareAxiosPost).not.toHaveBeenCalled()
    expect(retryStub).not.toHaveBeenCalled()
    expect(authStore.getToken()).toBe('expired-access-token')
  })

  it('retry that itself 401s does NOT loop — force logout', async () => {
    const config = {
      url: '/positions',
      method: 'get' as const,
      baseURL: '/api/v1',
      headers: { Authorization: 'Bearer x' },
      _retried: true,
    }
    const err = new Error('still 401') as AxiosError
    err.config = config as never
    err.response = {
      status: 401, data: {}, headers: {}, config: config as never,
      statusText: 'Unauthorized',
    }
    err.isAxiosError = true
    const handlers = (apiClient.interceptors.response as unknown as {
      handlers: Array<{ rejected?: (e: unknown) => unknown }>
    }).handlers
    const lastRejected = handlers[handlers.length - 1]?.rejected!

    await expect(lastRejected(err) as Promise<unknown>).rejects.toBeDefined()
    expect(mockBareAxiosPost).not.toHaveBeenCalled()
    expect(authStore.getToken()).toBeNull()
    expect(window.location.hash).toBe('#/login')
  })

  it('refresh succeeds even when backend omits new refreshToken (access-only rotation)', async () => {
    // Defensive: if the backend ever switches to access-only rotation
    // (currently it rotates both), keep the old refresh token rather
    // than wiping it.
    mockBareAxiosPost.mockResolvedValueOnce({
      data: { accessToken: 'access-only-new', refreshToken: undefined },
      status: 200, statusText: 'OK', headers: {}, config: {} as never,
    } as AxiosResponse)

    await fireInterceptorWith401()
    expect(authStore.getToken()).toBe('access-only-new')
    expect(authStore.getRefreshToken()).toBe('valid-refresh-token')
  })
})
