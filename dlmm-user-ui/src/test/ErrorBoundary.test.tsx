import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import i18n from '@/i18n'
import ErrorBoundary from '../components/ErrorBoundary'

function Boom(): JSX.Element {
  throw new Error('boom')
}

afterEach(() => {
  i18n.changeLanguage('ru')
  vi.restoreAllMocks()
})

describe('ErrorBoundary', () => {
  it('renders children when nothing throws', () => {
    render(<ErrorBoundary><span>ok</span></ErrorBoundary>)
    expect(screen.getByText('ok')).toBeInTheDocument()
  })

  it('contains a page crash with Russian copy', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    render(<ErrorBoundary><Boom /></ErrorBoundary>)
    expect(screen.getByText('Что-то пошло не так')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Обновить страницу' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'На главную' })).toBeInTheDocument()
  })

  it('renders English copy in en and navigates home', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    await i18n.changeLanguage('en')
    render(<ErrorBoundary><Boom /></ErrorBoundary>)
    expect(screen.getByText('Something went wrong')).toBeInTheDocument()
    expect(screen.getByText(/This page failed to load/)).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/[А-Яа-яЁё]/)
    fireEvent.click(screen.getByRole('button', { name: 'Go to home' }))
    expect(window.location.hash).toBe('#/')
  })
})
