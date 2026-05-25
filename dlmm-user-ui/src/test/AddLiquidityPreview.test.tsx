import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

/**
 * Sprint 11 G-22 — pins the AddLiquidityPreview behavior:
 *   1. Hidden entirely when inputs are insufficient (no API call fires).
 *   2. Renders the preview card when all inputs are provided.
 *   3. Shows in-range chip + TVL share + fee/day projection.
 *   4. Surfaces warnings list when backend returns them.
 *   5. Out-of-range case shows orange "Вне диапазона" chip.
 */

const previewMock = vi.fn()

vi.mock('@/api/services', () => ({
  pools: {
    previewAddLiquidity: (req: unknown) => previewMock(req),
  },
}))

import AddLiquidityPreview from '../components/AddLiquidityPreview'

const POOL_ID = 'pool-srub-sber'

function withQuery(node: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return <QueryClientProvider client={client}>{node}</QueryClientProvider>
}

describe('AddLiquidityPreview', () => {
  beforeEach(() => {
    previewMock.mockReset()
  })

  it('hidden + no API call when inputs incomplete', async () => {
    render(
      withQuery(
        <AddLiquidityPreview
          poolId={POOL_ID}
          amountX={null}
          amountY={null}
          binMin={null}
          binMax={null}
          strategy="SPOT"
          tokenYSymbol="SBER"
        />,
      ),
    )

    expect(screen.queryByTestId('add-liquidity-preview')).not.toBeInTheDocument()
    // Tick to confirm no debounced fire
    await waitFor(() => expect(previewMock).not.toHaveBeenCalled())
  })

  it('hidden when only one amount is filled', async () => {
    render(
      withQuery(
        <AddLiquidityPreview
          poolId={POOL_ID}
          amountX={1000}
          amountY={null}
          binMin={95}
          binMax={105}
          strategy="SPOT"
          tokenYSymbol="SBER"
        />,
      ),
    )
    expect(screen.queryByTestId('add-liquidity-preview')).not.toBeInTheDocument()
    await waitFor(() => expect(previewMock).not.toHaveBeenCalled())
  })

  it('renders in-range preview with TVL share + fee projection', async () => {
    previewMock.mockResolvedValue({
      tvlBeforeX: 10_000_000,
      tvlBeforeY: 10_000_000,
      tvlAfterX: 10_100_000,
      tvlAfterY: 10_100_000,
      tvlSharePct: 0.99,
      inRange: true,
      priceImpactBps: 0,
      depositedX: 100_000,
      depositedY: 100_000,
      binAllocations: [
        { binId: 95, amountX: 0, amountY: 20_000, liquidityShares: 20_000 },
        { binId: 100, amountX: 10_000, amountY: 10_000, liquidityShares: 20_000 },
        { binId: 105, amountX: 20_000, amountY: 0, liquidityShares: 20_000 },
      ],
      estimatedFeesPerDayY: 1485,
      warnings: [],
    })

    render(
      withQuery(
        <AddLiquidityPreview
          poolId={POOL_ID}
          amountX={100_000}
          amountY={100_000}
          binMin={95}
          binMax={105}
          strategy="SPOT"
          tokenYSymbol="SBER"
        />,
      ),
    )

    // Card visible, fetch fired
    expect(await screen.findByTestId('add-liquidity-preview')).toBeInTheDocument()
    await waitFor(() => expect(previewMock).toHaveBeenCalled())
    expect(previewMock).toHaveBeenLastCalledWith({
      poolId: POOL_ID,
      amountX: 100_000,
      amountY: 100_000,
      binRangeMin: 95,
      binRangeMax: 105,
      strategy: 'SPOT',
    })

    // In-range chip
    expect(await screen.findByText('В диапазоне')).toBeInTheDocument()
    // TVL share chip — "Доля TVL: 0.99%"
    expect(screen.getByText(/0\.99%/)).toBeInTheDocument()
    // Bin count
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('shows out-of-range chip + warning when inRange=false', async () => {
    previewMock.mockResolvedValue({
      tvlBeforeX: 10_000_000,
      tvlBeforeY: 10_000_000,
      tvlAfterX: 10_100_000,
      tvlAfterY: 10_000_000,
      tvlSharePct: 0.49,
      inRange: false,
      priceImpactBps: 50,
      depositedX: 100_000,
      depositedY: 0,
      binAllocations: [
        { binId: 110, amountX: 50_000, amountY: 0, liquidityShares: 50_000 },
      ],
      estimatedFeesPerDayY: 0,
      warnings: [
        'Ваш диапазон не включает активный бин — позиция простаивает пока цена не вернётся в диапазон.',
      ],
    })

    render(
      withQuery(
        <AddLiquidityPreview
          poolId={POOL_ID}
          amountX={100_000}
          amountY={100_000}
          binMin={110}
          binMax={115}
          strategy="SPOT"
          tokenYSymbol="SBER"
        />,
      ),
    )

    expect(await screen.findByText('Вне диапазона')).toBeInTheDocument()
    // Warning text rendered
    expect(
      await screen.findByText(/не включает активный бин/),
    ).toBeInTheDocument()
    // Fee projection shows em-dash when 0 (out-of-range)
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('shows error alert when backend errors', async () => {
    previewMock.mockRejectedValue(new Error('server-side boom'))

    render(
      withQuery(
        <AddLiquidityPreview
          poolId={POOL_ID}
          amountX={100_000}
          amountY={100_000}
          binMin={95}
          binMax={105}
          strategy="SPOT"
          tokenYSymbol="SBER"
        />,
      ),
    )

    expect(await screen.findByText('Не удалось рассчитать превью')).toBeInTheDocument()
  })
})
