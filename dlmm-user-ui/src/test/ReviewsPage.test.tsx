import { render, screen } from '@testing-library/react'
import { describe, it, expect, afterEach } from 'vitest'
import i18n from '@/i18n'
import ReviewsPage from '../pages/ReviewsPage'

afterEach(async () => {
  await i18n.changeLanguage('ru')
})

describe('ReviewsPage i18n', () => {
  it('renders Russian copy and dates by default', () => {
    render(<ReviewsPage />)
    expect(screen.getByText('Отзывы клиентов')).toBeInTheDocument()
    expect(screen.getByText('Алексей Морозов')).toBeInTheDocument()
    expect(screen.getByText('Казначей, ПАО «РосМеталл»')).toBeInTheDocument()
    expect(screen.getByText(/15 мая 2026/)).toBeInTheDocument()
  })

  it('switches names, roles, texts and dates to English', async () => {
    await i18n.changeLanguage('en')
    render(<ReviewsPage />)
    expect(screen.getByText('Customer reviews')).toBeInTheDocument()
    expect(screen.getByText('Alexey Morozov')).toBeInTheDocument()
    expect(screen.getByText('CFO, Agrotorg LLC')).toBeInTheDocument()
    expect(screen.getByText('15 May 2026')).toBeInTheDocument()
    expect(screen.queryByText(/[А-Яа-яЁё]/)).not.toBeInTheDocument()
  })

  it('renders all four reviews with avatar initials', () => {
    const { container } = render(<ReviewsPage />)
    expect(container.querySelectorAll('.sber-card')).toHaveLength(4)
    const initials = Array.from(container.querySelectorAll('.ant-avatar-string')).map((n) => n.textContent)
    expect(initials).toEqual(['А', 'Н', 'Д', 'И'])
  })
})
