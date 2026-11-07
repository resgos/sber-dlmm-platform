import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

/**
 * SK-01 — pins the bilingual chrome of the Сберкот assistant (sberkot.* keys)
 * and that the bubble/hearts carry no emoji (design rule: icons only).
 */

vi.mock('@/api/services', () => ({
  oracle: { getPrices: () => Promise.resolve([]) },
}))

import SberkotAssistant from '../components/sberkot/SberkotAssistant'
import { SBERKOT_CELEBRATE } from '../components/sberkot/events'
import i18n from '../i18n'

const EMOJI = /\p{Extended_Pictographic}/u

function renderAt(path = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <SberkotAssistant />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('SberkotAssistant i18n', () => {
  beforeEach(async () => {
    localStorage.clear()
    await i18n.changeLanguage('ru')
  })
  afterEach(async () => {
    await i18n.changeLanguage('ru')
  })

  it('RU: hint actions and close label come from sberkot.*', () => {
    renderAt('/')
    expect(screen.getByRole('button', { name: 'Понятно' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Скрыть подсказки' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Закрыть подсказку' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Погладить Сберкота' })).toBeInTheDocument()
  })

  it('EN: hint actions switch to English', async () => {
    await i18n.changeLanguage('en')
    renderAt('/')
    expect(await screen.findByRole('button', { name: 'Got it' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Hide hints' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Pet Sberkot' })).toHaveAttribute('title', 'Pet me')
  })

  it('celebration: localized title without emoji, empty message falls back to sberkot.done', async () => {
    await i18n.changeLanguage('en')
    renderAt('/')
    act(() => {
      window.dispatchEvent(new CustomEvent(SBERKOT_CELEBRATE, { detail: { message: '' } }))
    })
    expect(await screen.findByText('Great job!')).toBeInTheDocument()
    expect(screen.getByText('Done!')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Close Sberkot message' })).toBeInTheDocument()
    expect(EMOJI.test(document.body.textContent ?? '')).toBe(false)
  })
})
