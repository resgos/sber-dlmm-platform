import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import dayjs from 'dayjs'

const txMock = vi.fn()
const poolsMock = vi.fn()

vi.mock('@/api/services', () => ({
  transactions: {
    getMyTransactions: (...args: unknown[]) => txMock(...args),
  },
  pools: { getPools: (...args: unknown[]) => poolsMock(...args) },
}))

import TransactionsPage from '../pages/TransactionsPage'

const EMPTY_PAGE = { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 }

const renderPage = async () => {
  txMock.mockResolvedValue(EMPTY_PAGE)
  poolsMock.mockResolvedValue({ content: [], totalElements: 0 })
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <TransactionsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await waitFor(() => expect(txMock).toHaveBeenCalled())
}

// 2026-07-06 — the preset buttons are the first RangePicker surface that
// commits under synthetic interaction (calendar cells never fire onChange in
// automation — see project memory), which makes the date-filter path
// component-testable at last: preset click → handleDateChange → filters →
// query refetch with dateFrom/dateTo of the picked LOCAL day. The
// local-day → UTC-instant conversion downstream of these filters is covered
// by apiDates.test.ts (the service layer is mocked here).
describe('TransactionsPage — date presets', () => {
  beforeEach(() => {
    txMock.mockReset()
    poolsMock.mockReset()
  })

  it('clicking the «Сегодня» preset refetches with today as both day bounds', async () => {
    await renderPage()

    // open the range-picker dropdown, then commit via the preset button
    await userEvent.click(screen.getByPlaceholderText('Дата от'))
    await userEvent.click(await screen.findByText('Сегодня'))

    const today = dayjs().format('YYYY-MM-DD')
    await waitFor(() => {
      const filters = txMock.mock.calls.at(-1)?.[2]
      expect(filters).toMatchObject({ dateFrom: today, dateTo: today })
    })
  })

  it('clicking «7 дней» sets a 7-day window ending today', async () => {
    await renderPage()

    await userEvent.click(screen.getByPlaceholderText('Дата от'))
    await userEvent.click(await screen.findByText('7 дней'))

    await waitFor(() => {
      const filters = txMock.mock.calls.at(-1)?.[2]
      expect(filters).toMatchObject({
        dateFrom: dayjs().subtract(6, 'day').format('YYYY-MM-DD'),
        dateTo: dayjs().format('YYYY-MM-DD'),
      })
    })
  })
})
