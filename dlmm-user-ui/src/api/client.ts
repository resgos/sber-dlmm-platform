import axios, { AxiosError, AxiosRequestConfig } from 'axios'
import { authStore } from '@/store/authStore'
import { normalizeApiDates } from '@/lib/apiDates'

const apiClient = axios.create({
  baseURL: '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
})

apiClient.interceptors.request.use(
  (config) => {
    const token = authStore.getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error),
)

// R-04 — transparent JWT refresh on 401/403.
//
// Backend issues an access token with 30-min TTL; before this change a user
// who left the tab open past expiry hit infinite spinners ("ничего не
// работает") because every call returned 401/403 and the only escape was
// a hard logout (task #21). Now we transparently exchange the refresh token
// for a fresh access token and re-issue the original request.
//
// Concurrency: a single in-flight refreshPromise is shared across all
// callers — if 10 React-Query subscribers all fire and all 401 at once,
// they all await the SAME refresh and then each retry their own request.
// Without the singleton we'd burn 10 refresh tokens (and the backend
// rotates the refresh token on each call, so 9 of those rotations would
// race and invalidate each other).
let refreshPromise: Promise<string> | null = null

// Match by pathname segment to avoid false positives on query-string
// values like ?return=/auth/login. `apiClient` sends relative URLs
// (e.g. '/auth/login') so a startsWith check on the path is enough.
const AUTH_PATHS = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/logout']
const isAuthRoute = (url: string): boolean => {
  // Strip query string + leading baseURL fragments so '/auth/login?x=1'
  // and 'http://host/api/v1/auth/login' both match.
  const path = url.split('?')[0]
  return AUTH_PATHS.some((p) => path.endsWith(p))
}

/**
 * Perform the refresh call against /auth/refresh using a BARE axios
 * instance — not `apiClient` — so the refresh request itself can't recurse
 * back through this interceptor when /auth/refresh itself 401s (refresh
 * token also expired). Returns the new access token; throws on failure.
 *
 * On success, BOTH new access and new refresh tokens are persisted
 * (refresh-token rotation; backend issues a fresh refresh token every
 * call — see UserService#refresh).
 */
async function performRefresh(refreshToken: string): Promise<string> {
  // Bare axios — no interceptors, no auth header injection.
  const { data } = await axios.post<{ accessToken: string; refreshToken: string }>(
    '/api/v1/auth/refresh',
    { refreshToken },
    { headers: { 'Content-Type': 'application/json' } },
  )
  if (!data?.accessToken) {
    throw new Error('Refresh response missing accessToken')
  }
  authStore.setTokens(data.accessToken, data.refreshToken ?? refreshToken)
  return data.accessToken
}

/** Force logout + redirect to /login. Used when refresh is impossible or fails. */
function forceLogout(): void {
  authStore.logout()
  // Hash router — preserve the prior behaviour from task #21.
  window.location.hash = '#/login'
}

// Augment axios request config to carry our retry marker without polluting
// the public surface. _retried prevents infinite loops if the retried call
// itself 401s (e.g. backend bug, denylisted token).
interface RetryableRequestConfig extends AxiosRequestConfig {
  _retried?: boolean
}

apiClient.interceptors.response.use(
  (response) => {
    // 2026-07-05 — backend LocalDateTime arrives zoneless but IS UTC; append
    // 'Z' once here so every downstream dayjs()/Date parse renders the right
    // local time (a fresh notification read «3 часа назад» in Moscow).
    response.data = normalizeApiDates(response.data)
    return response
  },
  async (error: AxiosError) => {
    const status = error.response?.status
    const originalRequest = error.config as RetryableRequestConfig | undefined
    const url = (originalRequest?.url || '').toString()

    // Only auth-style failures matter here. Anything else (404, 500, 422,
    // network errors with no response) — propagate unchanged.
    const isAuthFailure = status === 401 || status === 403

    // Bail out if there's no config to retry (shouldn't happen in practice
    // but axios types allow it) or the call is one of the auth endpoints
    // themselves — refreshing on /auth/refresh would be infinite recursion;
    // refreshing on /auth/login is meaningless (you don't have a session
    // yet) and would mask the real "wrong password" error.
    if (!isAuthFailure || !originalRequest || isAuthRoute(url)) {
      return Promise.reject(error)
    }

    // No tokens at all — preserve the task #21 carve-out: a 403 on a
    // public endpoint with no token is a different bug and we shouldn't
    // touch auth state.
    const currentAccess = authStore.getToken()
    const refreshToken = authStore.getRefreshToken()
    if (!currentAccess) {
      return Promise.reject(error)
    }
    if (!refreshToken) {
      // We have an expired access token but no refresh token to exchange.
      // Nothing we can do — log the user out the old way.
      forceLogout()
      return Promise.reject(error)
    }

    // Avoid retry loops. If this request already came back through the
    // interceptor once, give up and force logout — the backend is either
    // still rejecting our new token (denylist, clock skew) or the retry
    // mechanism is broken; either way an infinite loop is worse than a
    // logout.
    if (originalRequest._retried) {
      forceLogout()
      return Promise.reject(error)
    }
    originalRequest._retried = true

    try {
      // Singleton in-flight refresh — concurrent 401s share one promise.
      // Clear the slot once the promise settles so a *later* expiry can
      // start a fresh refresh.
      if (!refreshPromise) {
        refreshPromise = performRefresh(refreshToken).finally(() => {
          refreshPromise = null
        })
      }
      const newAccessToken = await refreshPromise

      // Re-stamp the Authorization header on the retry so we don't reuse
      // the dead token captured at request-time. apiClient.request runs
      // the request interceptor again, which ALSO writes the new token
      // (it reads authStore.getToken() which we just updated), so this
      // is belt-and-braces — useful if the interceptor chain is ever
      // bypassed or rewired.
      if (!originalRequest.headers) {
        originalRequest.headers = {}
      }
      // Axios 1.x uses an AxiosHeaders class; both plain-object and the
      // class accept index-assignment so this works for either shape.
      ;(originalRequest.headers as Record<string, string>).Authorization =
        `Bearer ${newAccessToken}`

      // Retry through apiClient.request — explicit form, equivalent to
      // calling `apiClient(originalRequest)` but spyable in tests. Picks
      // up the same baseURL + interceptor chain.
      return await apiClient.request(originalRequest)
    } catch (refreshErr) {
      // Refresh itself failed — refresh token expired/revoked, or network
      // died. Either way the user can't recover silently; bounce to login.
      forceLogout()
      return Promise.reject(refreshErr)
    }
  },
)

export default apiClient
