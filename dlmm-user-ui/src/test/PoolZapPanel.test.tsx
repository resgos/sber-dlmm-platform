import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { Pool } from '@/api/types'

/**
 * PoolZapPanel (Zap in): i18n — подписи панели, кнопка-CTA и предупреждение о
 * нехватке баланса переводятся ключами poolZap.* в обоих языках.
 */

const balancesMock = vi.fn()
const quoteMock = vi.fn()

vi.mock('@/api/services', () => ({
  pools: {
    getSwapQuote: (req: unknown) => quoteMock(req),
    executeSwap: vi.fn(),
    addLiquidity: vi.fn(),
  },
  balances: {
    getMyBalances: () => balancesMock(),
  },
}))

import PoolZapPanel from '../components/PoolZapPanel'
import i18n from '../i18n'

const POOL = {
  id: 'pool-sber-srub',
  tokenXId: 'tok-sber',
  tokenYId: 'tok-srub',
  tokenXSymbol: 'SBER',
  tokenYSymbol: 'SRUB',
  activeBinId: 8388608,
} as unknown as Pool

function withQuery(node: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={client}>{node}</QueryClientProvider>
}

describe('PoolZapPanel', () => {
  beforeEach(() => {
    balancesMock.mockReset()
    quoteMock.mockReset()
    balancesMock.mockResolvedValue([{ tokenId: 'tok-srub', available: 25_000 }])
  })

  it('renders Russian labels by default', async () => {
    render(withQuery(<PoolZapPanel pool={POOL} />))
    expect(screen.getByText(/Внесите один токен/)).toBeInTheDocument()
    expect(screen.getByText('Внести SBER')).toBeInTheDocument()
    expect(screen.getByText('Внести SRUB')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Введите сумму/ })).toBeDisabled()
    expect(await screen.findByText(/Доступно:/)).toBeInTheDocument()
  })

  it('renders English labels after a language switch', async () => {
    const initialLng = i18n.language
    await i18n.changeLanguage('en')
    try {
      render(withQuery(<PoolZapPanel pool={POOL} />))
      expect(screen.getByText(/Deposit a single token/)).toBeInTheDocument()
      expect(screen.getByText('Deposit SBER')).toBeInTheDocument()
      expect(screen.getByText('Deposit SRUB')).toBeInTheDocument()
      expect(screen.getByText('Amount')).toBeInTheDocument()
      expect(screen.getByText('Swap')).toBeInTheDocument()
      expect(screen.getByText('Will be added to the pool ≈')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /Enter an amount/ })).toBeDisabled()
      expect(await screen.findByText(/Available:/)).toBeInTheDocument()
    } finally {
      await i18n.changeLanguage(initialLng)
    }
  })
})
