import axios from 'axios'
import { authStore } from '@/store/authStore'
import { normalizeApiDates } from '@/lib/apiDates'

const apiClient = axios.create({
  baseURL: '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
})

// Request interceptor: add Bearer token from authStore
apiClient.interceptors.request.use(
  (config) => {
    const token = authStore.getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  },
)

// Response interceptor: handle 401 by redirecting to login
apiClient.interceptors.response.use(
  (response) => {
    // 2026-07-05 — backend LocalDateTime arrives zoneless but IS UTC; append
    // 'Z' once here so every downstream dayjs()/Date parse renders the right
    // local time (AML «Время», users' created/last-login were shifted −3h).
    response.data = normalizeApiDates(response.data)
    return response
  },
  (error) => {
    if (error.response?.status === 401) {
      authStore.logout()
      window.location.href = '/login'
    }
    return Promise.reject(error)
  },
)

export default apiClient
