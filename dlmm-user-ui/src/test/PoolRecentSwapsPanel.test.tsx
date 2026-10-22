import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi, afterEach } from 'vitest'
import dayjs from 'dayjs'
import i18n from '@/i18n'
import type { Pool, Transaction } from '@/api/types'

const recentMock = vi.fn()
vi.mock('@/api/services', () => ({
  transactions: {
    getRecentPoolTransactions: (...args: unknown[]) => recentMock(...args),
  },
}))

import PoolRecentSwapsPanel from '../components/PoolRecentSwapsPanel'

const POOL = {
  id: 'pool-1',
  tokenXId: 'tx',
  tokenYId: 'ty',
  tokenXSymbol: 'SBER',
  tokenYSymbol: 'SRUB',
} as unknown as Pool

const SWAPS = [
  { id: 'aaaaaaaa-1', tokenInId: 'tx', amountIn: 10, amountOut: 3000, createdAt: dayjs().subtract(2, 'minute').toISOString() },
  { id: 'bbbbbbbb-2', tokenInId: 'ty', amountIn: 3000, amountOut: 10, createdAt: dayjs().subtract(2, 'minute').toISOString() },
] as unknown as Transaction[]

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <PoolRecentSwapsPanel pool={POOL} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  i18n.changeLanguage('ru')
  recentMock.mockReset()
})

describe('PoolRecentSwapsPanel i18n', () => {
  it('renders Russian copy and relative time by default', async () => {
    recentMock.mockResolvedValue(SWAPS)
    const { container } = renderPanel()
    expect(await screen.findByText('Продажа')).toBeInTheDocument()
    expect(screen.getByText('Покупка')).toBeInTheDocument()
    expect(screen.getByText('Последние обмены')).toBeInTheDocument()
    expect(container.textContent).toMatch(/2 минуты назад/)
  })

  it('renders English copy and English relative time in en (no global ru dayjs locale)', async () => {
    await i18n.changeLanguage('en')
    recentMock.mockResolvedValue(SWAPS)
    const { container } = renderPanel()
    expect(await screen.findByText('Sell')).toBeInTheDocument()
    expect(screen.getByText('Buy')).toBeInTheDocument()
    expect(screen.getByText('Recent swaps')).toBeInTheDocument()
    expect(screen.getByText('This pool only')).toBeInTheDocument()
    expect(container.textContent).toMatch(/2 minutes ago/)
    expect(container.textContent).not.toMatch(/[А-Яа-яЁё]/)
  })

  it('shows a localized empty state', async () => {
    await i18n.changeLanguage('en')
    recentMock.mockResolvedValue([])
    renderPanel()
    expect(await screen.findByText('No swaps yet')).toBeInTheDocument()
  })
})
