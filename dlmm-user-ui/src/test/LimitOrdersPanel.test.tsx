import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi, afterEach } from 'vitest'
import i18n from '@/i18n'
import type { Pool } from '@/api/types'
import { parseLocaleNumber } from '@/lib/format'

const getMineMock = vi.fn()
const balancesMock = vi.fn()
vi.mock('@/api/services', () => ({
  limitOrders: {
    getMine: (...args: unknown[]) => getMineMock(...args),
    create: vi.fn(),
    cancel: vi.fn(),
  },
  balances: { getMyBalances: (...args: unknown[]) => balancesMock(...args) },
}))

import LimitOrdersPanel from '../components/LimitOrdersPanel'

const POOL = {
  id: 'pool-1',
  tokenXId: 'tx',
  tokenYId: 'ty',
  tokenXSymbol: 'SBER',
  tokenYSymbol: 'SRUB',
  currentPrice: 312.45,
} as unknown as Pool

const ORDER = {
  id: 'o-1',
  side: 'SELL',
  amountIn: 10,
  amountOut: 3200,
  tokenInSymbol: 'SBER',
  tokenOutSymbol: 'SRUB',
  limitPrice: 320.5,
}

function renderPanel() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <LimitOrdersPanel pool={POOL} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  i18n.changeLanguage('ru')
  getMineMock.mockReset()
  balancesMock.mockReset()
})

describe('LimitOrdersPanel i18n', () => {
  it('renders Russian copy by default', async () => {
    getMineMock.mockResolvedValue([])
    balancesMock.mockResolvedValue([])
    renderPanel()
    expect(await screen.findByText('Нет открытых ордеров')).toBeInTheDocument()
    expect(screen.getByText('Купить SBER')).toBeInTheDocument()
    expect(screen.getByText('Цена исполнения')).toBeInTheDocument()
    expect(screen.getByText('Мои ордера в этом пуле')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Введите сумму' })).toBeDisabled()
    expect(screen.getByText('текущая: 312,45')).toBeInTheDocument()
  })

  it('renders English copy with no Cyrillic in en', async () => {
    await i18n.changeLanguage('en')
    getMineMock.mockResolvedValue([ORDER])
    balancesMock.mockResolvedValue([{ tokenId: 'ty', available: 50000 }])
    const { container } = renderPanel()
    expect(await screen.findByLabelText('Cancel order')).toBeInTheDocument()
    expect(screen.getByText('Buy SBER')).toBeInTheDocument()
    expect(screen.getByText('Limit price')).toBeInTheDocument()
    expect(screen.getByText('My orders in this pool')).toBeInTheDocument()
    expect(screen.getByText('market: 312.45')).toBeInTheDocument()
    expect(screen.getByText('@ 320.5')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enter an amount' })).toBeDisabled()
    expect(container.textContent ?? '').not.toMatch(/[А-Яа-яЁё]/)
  })
})

describe('parseLocaleNumber', () => {
  it('parses ru-RU grouped numbers with a decimal comma', () => {
    expect(parseLocaleNumber('1 234,5', 'ru-RU')).toBe(1234.5)
    expect(parseLocaleNumber('1 234 567', 'ru-RU')).toBe(1234567)
    expect(parseLocaleNumber('0.75', 'ru-RU')).toBe(0.75)
  })

  it('parses en-US grouped numbers with a decimal point', () => {
    expect(parseLocaleNumber('1,234.5', 'en-US')).toBe(1234.5)
    expect(parseLocaleNumber('12,345,678', 'en-US')).toBe(12345678)
  })

  it('maps empty input to 0 and garbage to NaN', () => {
    expect(parseLocaleNumber('', 'ru-RU')).toBe(0)
    expect(parseLocaleNumber(undefined, 'en-US')).toBe(0)
    expect(parseLocaleNumber('abc', 'ru-RU')).toBeNaN()
  })
})
