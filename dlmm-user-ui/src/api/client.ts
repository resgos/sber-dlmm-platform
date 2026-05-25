import axios from 'axios'
import { authStore } from '@/store/authStore'

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

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    // Task #21 — gateway's JwtValidationFilter returns 403 for
    // expired/invalid JWTs (not 401). Without handling 403 here, every
    // session that exceeds the 30-min token TTL leaves the user
    // staring at infinite spinners ("ничего не работает") because no
    // call ever succeeds and the UI never redirects to /login.
    // Only treat as auth failure when there's actually a token on
    // the client — a real 403 on a public endpoint without a token
    // is a different error and we shouldn't blow auth state away.
    const status = error.response?.status
    const url = (error.config?.url || '').toString()
    const isAuthFailure =
      (status === 401 || status === 403) &&
      !!authStore.getToken() &&
      !url.includes('/auth/login') &&
      !url.includes('/auth/register')
    if (isAuthFailure) {
      authStore.logout()
      window.location.hash = '#/login'
    }
    return Promise.reject(error)
  },
)

export default apiClient
