import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

const getDashboardMock = vi.fn()

vi.mock('@/api/services', () => ({
  admin: { getDashboard: () => getDashboardMock() },
}))

import DashboardPage from '../pages/DashboardPage'

// Sprint 9 #M-4 — StatCard now wraps tiles in <Link> when `to` is set.
// MemoryRouter is required for any DashboardPage render.
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

describe('DashboardPage (admin-ui)', () => {
  beforeEach(() => {
    getDashboardMock.mockReset()
  })

  it('shows spinner while loading', () => {
    // never resolves — keeps query in loading state
    getDashboardMock.mockReturnValue(new Promise(() => {}))
    const { container } = render(withQuery(<DashboardPage />))
    // AntD Spin "tip" prop is ignored in standalone (non-nest) mode and emits
    // a warning — assert on the spinner element instead of the tip text.
    // aria-busy="true" is the accessibility surface and the right thing to pin.
    expect(container.querySelector('[aria-busy="true"]')).toBeInTheDocument()
    expect(container.querySelector('.ant-spin-spinning')).toBeInTheDocument()
  })

  it('shows red alert on error', async () => {
    getDashboardMock.mockRejectedValue(new Error('boom'))
    render(withQuery(<DashboardPage />))
    expect(await screen.findByText('Не удалось загрузить дашборд')).toBeInTheDocument()
    expect(
      screen.getByText(/Невозможно получить метрики дашборда/),
    ).toBeInTheDocument()
  })

  it('renders hero + tile values from server payload', async () => {
    getDashboardMock.mockResolvedValue({
      totalUsers: 1234,
      verifiedUsers: 987,
      totalPools: 22,
      activePools: 20,
      totalTvlRub: 5_000_000,
      volume24hRub: 1_200_000,
      totalFeesCollectedRub: 88_000,
      transactionsToday: 17,
      activePositions: 42,
    })
    render(withQuery(<DashboardPage />))

    // Hero
    expect(await screen.findByText('Total Value Locked')).toBeInTheDocument()
    expect(screen.getByText('Комиссия за всё время')).toBeInTheDocument()

    // Tile titles — confirms each StatCard rendered for each metric.
    expect(screen.getByText('Всего пользователей')).toBeInTheDocument()
    expect(screen.getByText('Верифицированные')).toBeInTheDocument()
    expect(screen.getByText('Всего пулов')).toBeInTheDocument()
    expect(screen.getByText('Активные пулы')).toBeInTheDocument()
    expect(screen.getByText('Общий TVL')).toBeInTheDocument()
    expect(screen.getByText('Объём за 24ч')).toBeInTheDocument()
    expect(screen.getByText('Собрано комиссий')).toBeInTheDocument()
    expect(screen.getByText('Транзакций сегодня')).toBeInTheDocument()
  })

  it('singular/plural Russian agreement on active-pool counter', async () => {
    getDashboardMock.mockResolvedValue({
      totalUsers: 1,
      verifiedUsers: 0,
      totalPools: 1,
      activePools: 1,
      totalTvlRub: 0,
      volume24hRub: 0,
      totalFeesCollectedRub: 0,
      transactionsToday: 0,
      activePositions: 0,
    })
    const { container } = render(withQuery(<DashboardPage />))

    // Wait for the data to render — hero block appears once query resolves.
    await screen.findByText('Total Value Locked')

    // The meta line is split across multiple text nodes. Reduce DOM
    // textContent and check substrings directly (JS \b doesn't recognise
    // Cyrillic as word chars without the /u flag, so use literal anchors).
    const text = container.textContent ?? ''
    expect(text).toContain('1 активных пул ·')   // singular: "пул", not "пулов"
    expect(text).not.toContain('1 активных пулов')
  })

  it('computes KYC ratio when totalUsers > 0', async () => {
    getDashboardMock.mockResolvedValue({
      totalUsers: 200,
      verifiedUsers: 50, // 25%
      totalPools: 0,
      activePools: 0,
      totalTvlRub: 0,
      volume24hRub: 0,
      totalFeesCollectedRub: 0,
      transactionsToday: 0,
      activePositions: 0,
    })
    render(withQuery(<DashboardPage />))

    // Sprint 9-DS-r4 (CI fix): page renders the integer-rounded
    // percentage ("25%") via `Math.round((verified/total) * 100)`,
    // not a `.toFixed(1)` value. Earlier the page used AntD Statistic
    // with one-decimal precision; the layout refactor dropped the
    // decimal and this test wasn't updated.
    const kycTitle = await screen.findByText('Уровень верификации KYC')
    const card = kycTitle.closest('.ant-card')
    expect(card).not.toBeNull()
    const compact = (card?.textContent ?? '').replace(/\s+/g, '')
    expect(compact).toContain('25%')
  })
})
