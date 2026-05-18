import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

/**
 * Sprint 8 #C-9 — SwapPage is the most-complex page in user-ui and the
 * single most-trafficked critical path. Audit C-9 flagged its zero-test
 * coverage as a banking-grade risk. This file pins slippage math,
 * direction-flip, MAX-balance, alert lifecycle, and CTA state machine.
 */

const tokensMock = vi.fn()
const balancesMock = vi.fn()
const poolsMock = vi.fn()
const quoteMock = vi.fn()
const executeMock = vi.fn()

vi.mock('@/api/services', () => ({
  tokens: { getTokens: (...args: unknown[]) => tokensMock(...args) },
  balances: { getMyBalances: () => balancesMock() },
  pools: {
    getPools: (...args: unknown[]) => poolsMock(...args),
    getSwapQuote: (req: unknown) => quoteMock(req),
    executeSwap: (req: unknown) => executeMock(req),
  },
}))

import SwapPage from '../pages/SwapPage'

// ── fixtures ──

const SRUB = { id: 'tok-srub', symbol: 'SRUB', name: 'Sber Rouble', decimals: 6, tokenType: 'STABLECOIN' }
const SBER = { id: 'tok-sber', symbol: 'SBER', name: 'Sberbank', decimals: 6, tokenType: 'EQUITY_TOKEN' }
const GAZP = { id: 'tok-gazp', symbol: 'GAZP', name: 'Gazprom', decimals: 6, tokenType: 'EQUITY_TOKEN' }

const POOL = {
  id: 'pool-srub-sber',
  tokenXId: SRUB.id,
  tokenYId: SBER.id,
  tokenXSymbol: 'SRUB',
  tokenYSymbol: 'SBER',
  status: 'ACTIVE',
  binStep: 25,
  baseFeeBps: 30,
  activeBinId: 100,
  totalTvlX: 5_000_000,
  totalTvlY: 8_000,
  volume24h: 1_200_000,
  estimatedApy: 18.5,
}

const PAUSED_POOL = { ...POOL, id: 'pool-paused', status: 'PAUSED' as const }

const BALANCES = [
  { tokenId: SRUB.id, tokenSymbol: 'SRUB', available: 1_000_000, locked: 0 },
  { tokenId: SBER.id, tokenSymbol: 'SBER', available: 50, locked: 0 },
  { tokenId: GAZP.id, tokenSymbol: 'GAZP', available: 25, locked: 0 },
]

// ── helpers ──

function pageContent<T>(content: T[]) {
  return { content, totalElements: content.length, totalPages: 1, size: 100, number: 0 }
}

function withQuery(node: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return <QueryClientProvider client={client}>{node}</QueryClientProvider>
}

async function renderSwap(opts?: { pools?: typeof POOL[] }) {
  tokensMock.mockResolvedValue(pageContent([SRUB, SBER, GAZP]))
  balancesMock.mockResolvedValue(BALANCES)
  poolsMock.mockResolvedValue(pageContent(opts?.pools ?? [POOL]))
  const result = render(withQuery(<SwapPage />))
  // Wait for tokens to load so the Select dropdowns are populated.
  await waitFor(() => expect(tokensMock).toHaveBeenCalled())
  return result
}

/**
 * AntD Select doesn't render options to the React tree until the dropdown
 * opens, and the popup portals to document.body — so we can't use the
 * standard render(...).getByRole. Strategy:
 *   1. find the underlying combobox (one per swap box; getAllByRole returns
 *      both in DOM order),
 *   2. click it to open the dropdown,
 *   3. wait for the option text to appear anywhere in the document (portal),
 *   4. click it.
 *
 * The option label format is "${symbol} — ${name}" per the tokenOptions
 * mapping in SwapPage; we match on full text rather than `role="option"`
 * because AntD's option role/name behaviour shifts between minor releases.
 */
async function selectToken(side: 'in' | 'out', symbolLabelText: string) {
  const combos = screen.getAllByRole('combobox')
  const target = combos[side === 'in' ? 0 : 1]
  await userEvent.click(target)
  // AntD keeps both dropdowns in the DOM after one is closed (hidden via
  // display:none on .ant-select-dropdown-hidden). Without scoping, the
  // second selectToken() call finds the option text in both popups and
  // throws "multiple elements". Scope to the currently-visible dropdown.
  const option = await waitFor(() => {
    const visiblePopup = Array.from(document.querySelectorAll('.ant-select-dropdown'))
      .find((el) => !el.classList.contains('ant-select-dropdown-hidden'))
    if (!visiblePopup) throw new Error('No visible AntD Select dropdown after click')
    const opts = Array.from(visiblePopup.querySelectorAll('.ant-select-item-option'))
    const match = opts.find((o) => (o.textContent ?? '').includes(symbolLabelText))
    if (!match) throw new Error(`Option "${symbolLabelText}" not found in visible dropdown`)
    return match as HTMLElement
  })
  await userEvent.click(option)
}

const selectTokenIn = (label: string) => selectToken('in', label)
const selectTokenOut = (label: string) => selectToken('out', label)

function amountInInput() {
  return document.querySelectorAll('.sber-swap-box')[0].querySelector('input.ant-input-number-input') as HTMLInputElement
}

function amountOutInput() {
  return document.querySelectorAll('.sber-swap-box')[1].querySelector('input.ant-input-number-input') as HTMLInputElement
}

// ── tests ──

describe('SwapPage — initial render + CTA state machine', () => {
  beforeEach(() => {
    tokensMock.mockReset()
    balancesMock.mockReset()
    poolsMock.mockReset()
    quoteMock.mockReset()
    executeMock.mockReset()
  })

  it('renders headline + slippage settings button', async () => {
    await renderSwap()
    expect(screen.getByText('Обмен')).toBeInTheDocument()
    expect(screen.getByText(/Мгновенный своп между токенами/)).toBeInTheDocument()
    expect(screen.getByLabelText('Поменять направление')).toBeInTheDocument()
  })

  it('CTA reads "Выберите токены" when no tokens picked', async () => {
    await renderSwap()
    expect(screen.getByRole('button', { name: 'Выберите токены' })).toBeDisabled()
  })

  it('CTA reads "Введите сумму" once both tokens picked but amount empty', async () => {
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    expect(screen.getByRole('button', { name: 'Введите сумму' })).toBeDisabled()
  })

  it('CTA reads "Пул недоступен" when chosen pair has no ACTIVE pool', async () => {
    // Only a PAUSED pool exists for SRUB/SBER — selectedPool useMemo filters
    // to status='ACTIVE' so the warning branch fires.
    await renderSwap({ pools: [PAUSED_POOL] })
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    // Warning alert renders
    expect(await screen.findByText('Нет активного пула для выбранной пары')).toBeInTheDocument()
    // CTA reads "Пул недоступен" once an amount is entered.
    await userEvent.type(amountInInput(), '100')
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Пул недоступен' })).toBeDisabled()
    })
  })
})

describe('SwapPage — quote + slippage math', () => {
  beforeEach(() => {
    tokensMock.mockReset()
    balancesMock.mockReset()
    poolsMock.mockReset()
    quoteMock.mockReset()
    executeMock.mockReset()
  })

  it('fetches quote on amount entry and shows out-amount + fee + price impact', async () => {
    quoteMock.mockResolvedValue({
      poolId: POOL.id,
      amountIn: 10000,
      amountOut: 9970,
      fee: 30,
      priceImpact: 0.42,
    })
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '10000')

    await waitFor(() => expect(quoteMock).toHaveBeenCalled())
    // Quote endpoint receives the right shape — pinning the request payload.
    expect(quoteMock).toHaveBeenLastCalledWith({
      poolId: POOL.id,
      tokenInId: SRUB.id,
      amountIn: 10000,
    })

    // Receive-side input populates from quote.amountOut.
    await waitFor(() => {
      expect(amountOutInput().value).toMatch(/9[\s ]?970/)
    })

    // Price-impact + fee rows render.
    expect(screen.getByText('Влияние на цену')).toBeInTheDocument()
    expect(screen.getByText('0.42%')).toBeInTheDocument()
    expect(screen.getByText('Курс')).toBeInTheDocument()
  })

  it('default slippage 0.5%: minAmountOut = floor(9970 * 0.995) = 9920', async () => {
    quoteMock.mockResolvedValue({
      poolId: POOL.id, amountIn: 10000, amountOut: 9970, fee: 30, priceImpact: 0.42,
    })
    executeMock.mockResolvedValue(undefined)
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '10000')
    await screen.findByText('0.42%')

    await userEvent.click(screen.getByRole('button', { name: 'Обменять' }))

    await waitFor(() => expect(executeMock).toHaveBeenCalled())
    const swapBody = executeMock.mock.calls[0][0] as { minAmountOut: number; idempotencyKey: string }
    expect(swapBody.minAmountOut).toBe(9920) // floor(9970 × 0.995)
    expect(typeof swapBody.idempotencyKey).toBe('string')
    expect(swapBody.idempotencyKey.length).toBeGreaterThan(8)
  })

  it('quote loading state surfaces "Расчёт маршрута…" before resolution', async () => {
    // Never-resolving promise keeps the query in loading state.
    quoteMock.mockReturnValue(new Promise(() => {}))
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '100')

    expect(await screen.findByText(/Расчёт маршрута/)).toBeInTheDocument()
  })
})

describe('SwapPage — direction flip + MAX button', () => {
  beforeEach(() => {
    tokensMock.mockReset()
    balancesMock.mockReset()
    poolsMock.mockReset()
    quoteMock.mockReset()
    executeMock.mockReset()
  })

  it('direction-flip swaps token sides and clears amount', async () => {
    quoteMock.mockResolvedValue({
      poolId: POOL.id, amountIn: 100, amountOut: 99, fee: 1, priceImpact: 0.1,
    })
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '100')

    await waitFor(() => expect(amountInInput().value).toBe('100'))

    await userEvent.click(screen.getByLabelText('Поменять направление'))

    // Amount cleared after flip — prevents accidental same-amount swap in
    // the OPPOSITE direction (which would silently use the wrong tokenIn).
    await waitFor(() => expect(amountInInput().value).toBe(''))

    // From-side now shows SBER (the previous to-token). Visible via the
    // ant-select-selection-item rendered by labelRender.
    const fromBox = document.querySelectorAll('.sber-swap-box')[0]
    await waitFor(() => {
      expect(fromBox.textContent).toContain('SBER')
    })
  })

  it('MAX button fills amountIn with the in-token balance', async () => {
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')

    // Balance row shows "Доступно: 1 000 000 MAX"
    const maxButton = await screen.findByRole('button', { name: 'MAX' })
    await userEvent.click(maxButton)

    await waitFor(() => {
      // AntD InputNumber formats — value becomes "1000000" raw in the input.
      expect(amountInInput().value).toMatch(/1[\s ,]?000[\s ,]?000/)
    })
  })
})

describe('SwapPage — swap mutation outcomes', () => {
  beforeEach(() => {
    tokensMock.mockReset()
    balancesMock.mockReset()
    poolsMock.mockReset()
    quoteMock.mockReset()
    executeMock.mockReset()
  })

  it('successful swap shows green alert + clears amountIn', async () => {
    quoteMock.mockResolvedValue({
      poolId: POOL.id, amountIn: 100, amountOut: 99, fee: 1, priceImpact: 0.1,
    })
    executeMock.mockResolvedValue(undefined)
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '100')
    await screen.findByText('0.10%')

    await userEvent.click(screen.getByRole('button', { name: 'Обменять' }))

    expect(await screen.findByText('Обмен выполнен успешно!')).toBeInTheDocument()
    await waitFor(() => expect(amountInInput().value).toBe(''))
  })

  it('failed swap shows server error message in red alert', async () => {
    quoteMock.mockResolvedValue({
      poolId: POOL.id, amountIn: 100, amountOut: 99, fee: 1, priceImpact: 0.1,
    })
    executeMock.mockRejectedValue({
      response: { data: { message: 'Недостаточно средств на балансе' } },
    })
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '100')
    await screen.findByText('0.10%')

    await userEvent.click(screen.getByRole('button', { name: 'Обменять' }))

    expect(await screen.findByText('Недостаточно средств на балансе')).toBeInTheDocument()
  })

  it('failed swap without server message falls back to generic text', async () => {
    quoteMock.mockResolvedValue({
      poolId: POOL.id, amountIn: 100, amountOut: 99, fee: 1, priceImpact: 0.1,
    })
    executeMock.mockRejectedValue(new Error('network down'))
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '100')
    await screen.findByText('0.10%')

    await userEvent.click(screen.getByRole('button', { name: 'Обменять' }))

    expect(await screen.findByText('Ошибка при выполнении обмена')).toBeInTheDocument()
  })
})

describe('SwapPage — quote panel formatting', () => {
  beforeEach(() => {
    tokensMock.mockReset()
    balancesMock.mockReset()
    poolsMock.mockReset()
    quoteMock.mockReset()
    executeMock.mockReset()
  })

  it('quote panel surfaces the pool route tag with token symbols', async () => {
    quoteMock.mockResolvedValue({
      poolId: POOL.id, amountIn: 100, amountOut: 99, fee: 1, priceImpact: 0.1,
    })
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '100')

    expect(await screen.findByText(/через пул SRUB\/SBER/)).toBeInTheDocument()
    // Pool meta: "30 bps · допуск 0.5%"
    expect(screen.getByText(/30 bps/)).toBeInTheDocument()
    expect(screen.getByText(/допуск 0\.5%/)).toBeInTheDocument()
  })

  it('quote panel shows mathematically-derived rate (1 SRUB ≈ 0.99 SBER)', async () => {
    quoteMock.mockResolvedValue({
      poolId: POOL.id, amountIn: 100, amountOut: 99, fee: 1, priceImpact: 0.1,
    })
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '100')

    // Rate row text: "1 SRUB ≈ 0.990000 SBER"
    await waitFor(() => {
      expect(screen.getByText(/1 SRUB.*0\.990000.*SBER/)).toBeInTheDocument()
    })
  })

  it('quote panel shows min-amount-out reflecting default 0.5% slippage', async () => {
    quoteMock.mockResolvedValue({
      poolId: POOL.id, amountIn: 100, amountOut: 99, fee: 1, priceImpact: 0.1,
    })
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '100')

    // floor(99 × 0.995) = 98
    await waitFor(() => {
      expect(screen.getByText('Мин. к получению')).toBeInTheDocument()
    })
    // The row value cell renders "98 SBER"
    expect(screen.getByText(/98 SBER/)).toBeInTheDocument()
  })
})
