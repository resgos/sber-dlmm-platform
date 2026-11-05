import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi, afterEach } from 'vitest'
import i18n from '@/i18n'

vi.mock('@/api/services', () => ({
  tokens: { getTokens: vi.fn().mockResolvedValue({ content: [{ id: 'sspas', symbol: 'SSPAS' }] }) },
  balances: { getMyBalances: vi.fn().mockResolvedValue([{ tokenId: 'sspas', available: 1500 }]) },
  spasibo: { convertToRub: vi.fn() },
}))

import SpasiboWidget from '../components/SpasiboWidget'

function renderWidget() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <SpasiboWidget />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  i18n.changeLanguage('ru')
})

describe('SpasiboWidget i18n', () => {
  it('renders Russian copy by default', async () => {
    renderWidget()
    expect(await screen.findByText('СберСпасибо')).toBeInTheDocument()
    expect(screen.getByText('Доступно баллов')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Сколько баллов?')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /В рубли/ })).toBeDisabled()
  })

  it('renders English copy in en', async () => {
    await i18n.changeLanguage('en')
    renderWidget()
    expect(await screen.findByText('SberSpasibo')).toBeInTheDocument()
    expect(screen.getByText('Points available')).toBeInTheDocument()
    expect(screen.getByText('Convert points to SRUB at 1:1 (no fee)')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('How many points?')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /To rubles/ })).toBeInTheDocument()
  })
})
