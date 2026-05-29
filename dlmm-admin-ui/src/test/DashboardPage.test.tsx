import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

// DS-02 — the dashboard now fans out to three services (dashboard summary,
// recent transactions, pools) plus an /actuator/health fetch. Mock all of
// them so the component renders past its loading gate.
const getDashboardMock = vi.fn()
const getTransactionsMock = vi.fn()
const getPoolsMock = vi.fn()

vi.mock('@/api/services', () => ({
  admin: { getDashboard: () => getDashboardMock() },
  transactions: { getTransactions: (...a: unknown[]) => getTransactionsMock(...a) },
  pools: { getPools: (...a: unknown[]) => getPoolsMock(...a) },
}))

import DashboardPage from '../pages/DashboardPage'

function withQuery(node: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return (
    <MemoryRouter>
      <QueryClientProvider client={client}>{node}</QueryClientProvider>
    </MemoryRouter>
  )
}

const emptyPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }

beforeEach(() => {
  getDashboardMock.mockReset()
  getTransactionsMock.mockReset().mockResolvedValue(emptyPage)
  getPoolsMock.mockReset().mockResolvedValue(emptyPage)
  // /actuator/health fetch — default to a reachable, all-UP gateway.
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: true,
      text: async () => JSON.stringify({ status: 'UP', components: {} }),
    })),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('DashboardPage (admin-ui) — DS-02 ops dashboard', () => {
  it('shows spinner while loading', () => {
    getDashboardMock.mockReturnValue(new Promise(() => {})) // never resolves
    const { container } = render(withQuery(<DashboardPage />))
    expect(container.querySelector('[aria-busy="true"]')).toBeInTheDocument()
    expect(container.querySelector('.ant-spin-spinning')).toBeInTheDocument()
  })

  it('shows red alert on error', async () => {
    getDashboardMock.mockRejectedValue(new Error('boom'))
    render(withQuery(<DashboardPage />))
    expect(await screen.findByText('Не удалось загрузить дашборд')).toBeInTheDocument()
    expect(screen.getByText(/Невозможно получить метрики дашборда/)).toBeInTheDocument()
  })

  it('renders header, KPI labels and section titles from server payload', async () => {
    getDashboardMock.mockResolvedValue({
      totalUsers: 1234,
      verifiedUsers: 987,
      totalPools: 22,
      activePools: 20,
      totalTvlRub: 2_420_000_000,
      volume24hRub: 184_600_000,
      totalFeesCollectedRub: 12_740_000,
      transactionsToday: 1248,
      activePositions: 1284,
    })
    render(withQuery(<DashboardPage />))

    // Header
    expect(await screen.findByText('Обзор платформы')).toBeInTheDocument()
    expect(screen.getByText('Аналитика / Обзор')).toBeInTheDocument()

    // KPI tile labels. ("Объём 24ч" also appears as a pool-table column
    // header, so it can match more than once — assert presence, not unique.)
    expect(screen.getByText('Общий TVL')).toBeInTheDocument()
    expect(screen.getAllByText('Объём 24ч').length).toBeGreaterThan(0)
    expect(screen.getByText('Собрано комиссий')).toBeInTheDocument()
    expect(screen.getByText('Активные позиции')).toBeInTheDocument()

    // Section headings
    expect(screen.getByText('TVL за период')).toBeInTheDocument()
    expect(screen.getByText('Объём по пулам')).toBeInTheDocument()
    expect(screen.getByText('Последние операции')).toBeInTheDocument()
    expect(screen.getByText('Здоровье сервисов')).toBeInTheDocument()
    expect(screen.getByText('Здоровье пулов')).toBeInTheDocument()
  })

  it('formats the TVL headline from the real payload (compact ₽)', async () => {
    getDashboardMock.mockResolvedValue({
      totalUsers: 0,
      verifiedUsers: 0,
      totalPools: 22,
      activePools: 20,
      totalTvlRub: 2_420_000_000, // 2,42 млрд ₽
      volume24hRub: 0,
      totalFeesCollectedRub: 0,
      transactionsToday: 0,
      activePositions: 0,
    })
    const { container } = render(withQuery(<DashboardPage />))
    await screen.findByText('Общий TVL')
    const text = (container.textContent ?? '').replace(/\s+/g, ' ')
    // Real value rendered as the headline number + unit. The shared
    // `formatRub` compact formatter uses a `.` decimal (2.42), not a comma —
    // the point is that the REAL 2.42 млрд ₽ is shown, never a mock number.
    expect(text).toContain('2.42')
    expect(text).toContain('млрд ₽')
  })

  it('renders the 8-service health strip', async () => {
    getDashboardMock.mockResolvedValue({
      totalUsers: 0, verifiedUsers: 0, totalPools: 0, activePools: 0,
      totalTvlRub: 0, volume24hRub: 0, totalFeesCollectedRub: 0,
      transactionsToday: 0, activePositions: 0,
    })
    const { container } = render(withQuery(<DashboardPage />))
    await screen.findByText('Обзор платформы')
    await waitFor(() => {
      // 8 dots in the header strip + 8 in the detail card = at least 8 of each.
      expect(container.querySelectorAll('.ds-svc-strip .ds-dot').length).toBe(8)
    })
  })
})
