import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi, afterEach } from 'vitest'
import i18n from '@/i18n'
import type { PoolDetail } from '@/api/types'

const getPoolMock = vi.fn()
const recentMock = vi.fn()
vi.mock('@/api/services', () => ({
  pools: { getPool: (...args: unknown[]) => getPoolMock(...args) },
  transactions: { getRecentPoolTransactions: (...args: unknown[]) => recentMock(...args) },
}))

import OrderBook from '../components/OrderBook'

const POOL = {
  id: 'pool-1',
  tokenXId: 'tx',
  tokenYId: 'ty',
  tokenXSymbol: 'SBER',
  tokenYSymbol: 'SRUB',
  activeBinId: 100,
  currentPrice: 300,
  totalTvlX: 50,
  totalTvlY: 15000,
  bins: [
    { binId: 99, price: 299.4, reserveX: 0, reserveY: 6000 },
    { binId: 100, price: 300, reserveX: 10, reserveY: 3000 },
    { binId: 101, price: 300.6, reserveX: 40, reserveY: 0 },
  ],
} as unknown as PoolDetail

function renderBook() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <OrderBook poolId="pool-1" />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  i18n.changeLanguage('ru')
  getPoolMock.mockReset()
  recentMock.mockReset()
})

describe('OrderBook i18n', () => {
  it('renders Russian copy by default', async () => {
    getPoolMock.mockResolvedValue(POOL)
    recentMock.mockResolvedValue([])
    renderBook()
    expect(await screen.findByText('Цена (SRUB)')).toBeInTheDocument()
    expect(screen.getAllByText('Стакан').length).toBeGreaterThan(0)
    expect(screen.getByText('из бинов пула')).toBeInTheDocument()
    expect(screen.getByLabelText('Заявки на продажу')).toBeInTheDocument()
    expect(screen.getByLabelText('Заявки на покупку')).toBeInTheDocument()
    // (300.6 − 299.4) / 300 = 0.40 %
    expect(screen.getByText('спред 0.40%')).toBeInTheDocument()
  })

  it('renders English copy with no Cyrillic in en', async () => {
    await i18n.changeLanguage('en')
    getPoolMock.mockResolvedValue(POOL)
    recentMock.mockResolvedValue([])
    const { container } = renderBook()
    expect(await screen.findByText('Price (SRUB)')).toBeInTheDocument()
    expect(screen.getByText('Size (SBER/SRUB)')).toBeInTheDocument()
    expect(screen.getByText('from pool bins')).toBeInTheDocument()
    expect(screen.getByLabelText('Sell orders')).toBeInTheDocument()
    expect(screen.getByLabelText('Buy orders')).toBeInTheDocument()
    expect(screen.getByText('spread 0.40%')).toBeInTheDocument()
    expect(container.textContent).not.toMatch(/[А-Яа-яЁё]/)
  })

  it('shows a localized load error', async () => {
    await i18n.changeLanguage('en')
    getPoolMock.mockRejectedValue(new Error('boom'))
    renderBook()
    expect(await screen.findByText('Failed to load the order book')).toBeInTheDocument()
  })
})
