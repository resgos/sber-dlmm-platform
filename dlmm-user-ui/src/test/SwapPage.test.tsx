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
const oracleMock = vi.fn(() => Promise.resolve([] as unknown[]))

vi.mock('@/api/services', () => ({
  tokens: { getTokens: (...args: unknown[]) => tokensMock(...args) },
  balances: { getMyBalances: () => balancesMock() },
  pools: {
    getPools: (...args: unknown[]) => poolsMock(...args),
    getSwapQuote: (req: unknown) => quoteMock(req),
    executeSwap: (req: unknown) => executeMock(req),
  },
  // Market-reference ticker feed; empty by default — the marketRef row hides.
  oracle: { getPrices: () => oracleMock() },
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
 * Sprint 10: the token picker is the TokenSelect modal, not an AntD Select.
 * Each swap box has one `.sber-tokensel-trigger` button; clicking it opens a
 * modal (portaled to document.body, `destroyOnClose`) whose `.sber-tokensel-list`
 * holds one `.sber-tokensel-row` per token. Strategy:
 *   1. find the trigger inside the in/out `.sber-swap-box`,
 *   2. click it to open the modal,
 *   3. wait for the row whose symbol span matches, scoped to the *visible*
 *      modal wrap (a just-closed picker may still be animating out),
 *   4. click the row.
 *
 * Call-sites still pass the old "${symbol} — ${name}" label, so we match on the
 * symbol (text before " — ") against `.sber-tokensel-row__sym`.
 */
async function selectToken(side: 'in' | 'out', symbolLabelText: string) {
  const symbol = symbolLabelText.split(' — ')[0].trim()
  const box = document.querySelectorAll('.sber-swap-box')[side === 'in' ? 0 : 1]
  const trigger = box.querySelector('.sber-tokensel-trigger') as HTMLElement
  await userEvent.click(trigger)
  const row = await waitFor(() => {
    const wrap = Array.from(document.querySelectorAll('.ant-modal-wrap')).find(
      (el) => (el as HTMLElement).style.display !== 'none',
    )
    const scope: ParentNode = wrap ?? document
    const rows = Array.from(scope.querySelectorAll('.sber-tokensel-row'))
    const match = rows.find(
      (r) => (r.querySelector('.sber-tokensel-row__sym')?.textContent ?? '') === symbol,
    )
    if (!match) throw new Error(`Token "${symbol}" not found in open picker`)
    return match as HTMLElement
  })
  await userEvent.click(row)
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
    // Sprint 9-DS-r4 (CI fix): page now renders BOTH the inline-
    // quote rows and a right-rail SwapInfoPanel that surfaces the
    // same labels, so the same text appears 2× per assertion.
    // `getAllByText` accepts the duplication; we only need the rows
    // to exist somewhere on the page.
    expect(screen.getAllByText('Влияние на цену').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('0.42%').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('Курс').length).toBeGreaterThanOrEqual(1)
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
    await screen.findAllByText('0.42%')

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
    // Sprint 9-DS-r4 (CI fix) — quote row appears in both inline-
    // quote and right-rail SwapInfoPanel; use findAllByText.
    await screen.findAllByText('0.10%')

    await userEvent.click(screen.getByRole('button', { name: 'Обменять' }))

    expect(await screen.findByText('Обмен выполнен успешно!')).toBeInTheDocument()
    await waitFor(() => expect(amountInInput().value).toBe(''))
  })

  it('failed swap maps backend errorCode to a friendly message (no UUID leak)', async () => {
    quoteMock.mockResolvedValue({
      poolId: POOL.id, amountIn: 100, amountOut: 99, fee: 1, priceImpact: 0.1,
    })
    // Backend message leaks internal UUIDs; the UI must show the friendly
    // mapped text for the errorCode, NOT this raw message.
    executeMock.mockRejectedValue({
      response: { data: { errorCode: 'INSUFFICIENT_BALANCE', message: 'Insufficient balance for user a0000000-0000-0000-0000-000000000002 token b0000000-0000-0000-0000-000000000001' } },
    })
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '100')
    // Sprint 9-DS-r4 (CI fix) — quote row appears in both inline-
    // quote and right-rail SwapInfoPanel; use findAllByText.
    await screen.findAllByText('0.10%')

    await userEvent.click(screen.getByRole('button', { name: 'Обменять' }))

    expect(await screen.findByText('Недостаточно средств на балансе для этой операции.')).toBeInTheDocument()
    // the raw UUID-leaking backend message must NOT be rendered
    expect(screen.queryByText(/a0000000-/)).not.toBeInTheDocument()
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
    // Sprint 9-DS-r4 (CI fix) — quote row appears in both inline-
    // quote and right-rail SwapInfoPanel; use findAllByText.
    await screen.findAllByText('0.10%')

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
    // Sprint 9 — Pool meta now reads "комиссия 0.3% · допуск 0.5%"
    // (was "30 bps · допуск 0.5%"). User-friendly per UX feedback.
    // Sprint 9-DS-r4 (CI fix) — "комиссия 0.3%" also appears in
    // the right-rail SwapInfoPanel pool meta; allow multiplicity.
    expect(screen.getAllByText(/комиссия 0\.3%/).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText(/допуск 0\.5%/).length).toBeGreaterThanOrEqual(1)
  })

  it('quote panel shows mathematically-derived rate (1 SRUB ≈ 0.99 SBER)', async () => {
    quoteMock.mockResolvedValue({
      poolId: POOL.id, amountIn: 100, amountOut: 99, fee: 1, priceImpact: 0.1,
    })
    await renderSwap()
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '100')

    // Rate row text: "1 SRUB ≈ 0,99 SBER" (Sprint 16 — adaptive ru-RU rate
    // format, now shown BOTH ways: this forward line + a reverse "1 SBER ≈ …").
    // The rate appears in both the inline quote panel and the right-rail panel.
    await waitFor(() => {
      expect(screen.getAllByText(/1 SRUB.*0,99.*SBER/).length).toBeGreaterThanOrEqual(1)
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
    // Sprint 9-DS-r4 (CI fix) — label is now "Мин. к получению (0.5%)"
    // with the effective-slippage in parens (page edit). Match via
    // regex so the test isn't tied to the literal slippage number.
    await waitFor(() => {
      expect(screen.getAllByText(/Мин\. к получению/).length).toBeGreaterThanOrEqual(1)
    })
    // The row value cell renders "98 SBER" — may appear in inline
    // quote AND right-rail; multiplicity acceptable.
    expect(screen.getAllByText(/98 SBER/).length).toBeGreaterThanOrEqual(1)
  })
})

describe('SwapPage — market-price reference (vs oracle)', () => {
  // Correct X/SRUB ordering (asset = tokenX, SRUB = tokenY), unlike the reversed
  // base POOL fixture; carries a spot currentPrice to compare against the oracle.
  const SBER_POOL = {
    ...POOL,
    tokenXId: SBER.id,
    tokenYId: SRUB.id,
    tokenXSymbol: 'SBER',
    tokenYSymbol: 'SRUB',
    currentPrice: 221.6,
  }
  const aQuote = {
    poolId: SBER_POOL.id, tokenInId: SRUB.id, tokenOutId: SBER.id,
    amountIn: 10000, amountOut: 45, fee: 30, feeBps: 30, binsCrossed: 1,
    estimatedPrice: 221.6, priceImpact: 0.42,
  }

  beforeEach(() => {
    tokensMock.mockReset(); balancesMock.mockReset(); poolsMock.mockReset()
    quoteMock.mockReset(); executeMock.mockReset()
    oracleMock.mockReset(); oracleMock.mockResolvedValue([])
  })

  it('shows the oracle price + signed deviation when the pool is off-market', async () => {
    oracleMock.mockResolvedValue([{ symbol: 'SBER', price: 200, change24h: 0, source: 'MOEX' }])
    quoteMock.mockResolvedValue(aQuote)
    await renderSwap({ pools: [SBER_POOL] })
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '10000')
    // pool spot 221.6 vs oracle 200 → +10.80% → wide band.
    expect(await screen.findByText(/Рыночная цена/)).toBeInTheDocument()
    expect(await screen.findByText('+10.80% к рынку')).toBeInTheDocument()
  })

  it('hides the reference when the oracle has no feed for that asset', async () => {
    oracleMock.mockResolvedValue([]) // no SBER price
    quoteMock.mockResolvedValue(aQuote)
    await renderSwap({ pools: [SBER_POOL] })
    await selectTokenIn('SRUB — Sber Rouble')
    await selectTokenOut('SBER — Sberbank')
    await userEvent.type(amountInInput(), '10000')
    // the quote still renders (rate row), but no market-reference row.
    expect(await screen.findByText('Курс')).toBeInTheDocument()
    expect(screen.queryByText(/Рыночная цена/)).not.toBeInTheDocument()
  })
})
