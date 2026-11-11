import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, it, expect, vi, afterEach } from 'vitest'
import i18n from '@/i18n'
import type { PoolDetail } from '@/api/types'

// recharts' ResponsiveContainer needs ResizeObserver, which jsdom lacks.
if (!('ResizeObserver' in globalThis)) {
  ;(globalThis as Record<string, unknown>).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}

const getPoolMock = vi.fn()
vi.mock('@/api/services', () => ({
  pools: { getPool: (...args: unknown[]) => getPoolMock(...args) },
}))

import BinLiquidityChart from '../components/BinLiquidityChart'

const POOL = {
  id: 'pool-1',
  tokenXSymbol: 'SBER',
  tokenYSymbol: 'SRUB',
  activeBinId: 100,
  bins: [
    { binId: 99, price: 299.4, reserveX: 0, reserveY: 6000, liquidity: 6000 },
    { binId: 100, price: 300, reserveX: 10, reserveY: 3000, liquidity: 6000 },
    { binId: 101, price: 300.6, reserveX: 40, reserveY: 0, liquidity: 12024 },
  ],
} as unknown as PoolDetail

function renderChart() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <BinLiquidityChart
        poolId="pool-1"
        userBinRanges={[{ binMin: 100, binMax: 101 }]}
        pendingPreview={{ binMin: 99, binMax: 101, strategy: 'CURVE' }}
        onRangeDrag={() => {}}
      />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  i18n.changeLanguage('ru')
  getPoolMock.mockReset()
})

describe('BinLiquidityChart i18n', () => {
  it('renders Russian legend and controls by default', async () => {
    getPoolMock.mockResolvedValue(POOL)
    renderChart()
    expect(await screen.findByText('Резерв SRUB')).toBeInTheDocument()
    expect(screen.getByText('Резерв SBER')).toBeInTheDocument()
    expect(screen.getByText('Текущая цена: 300.0000')).toBeInTheDocument()
    expect(screen.getByText('Ваши бины')).toBeInTheDocument()
    expect(screen.getByText('Превью: Концентрированная')).toBeInTheDocument()
    expect(screen.getByLabelText('Приблизить')).toBeInTheDocument()
    expect(screen.getByLabelText('К текущей цене')).toBeInTheDocument()
    expect(screen.getByLabelText('Отдалить')).toBeInTheDocument()
  })

  it('renders English copy with no Cyrillic in en', async () => {
    await i18n.changeLanguage('en')
    getPoolMock.mockResolvedValue(POOL)
    const { container } = renderChart()
    expect(await screen.findByText('SRUB reserve')).toBeInTheDocument()
    expect(screen.getByText('SBER reserve')).toBeInTheDocument()
    expect(screen.getByText('Current price: 300.0000')).toBeInTheDocument()
    expect(screen.getByText('Your bins')).toBeInTheDocument()
    expect(screen.getByText('Preview: Concentrated')).toBeInTheDocument()
    expect(screen.getByLabelText('Zoom in')).toBeInTheDocument()
    expect(screen.getByLabelText('Back to current price')).toBeInTheDocument()
    expect(screen.getByLabelText('Zoom out')).toBeInTheDocument()
    expect(screen.getByText('Drag across the chart to pick a bin range to add')).toBeInTheDocument()
    expect(container.textContent).not.toMatch(/[А-Яа-яЁё]/)
  })

  it('shows a localized load error', async () => {
    await i18n.changeLanguage('en')
    getPoolMock.mockRejectedValue(new Error('boom'))
    renderChart()
    expect(await screen.findByText('Failed to load bin data')).toBeInTheDocument()
  })

  it('shows localized empty state when the pool has no bins', async () => {
    await i18n.changeLanguage('en')
    getPoolMock.mockResolvedValue({ ...POOL, bins: [] })
    renderChart()
    expect(await screen.findByText('No bin data')).toBeInTheDocument()
  })
})
