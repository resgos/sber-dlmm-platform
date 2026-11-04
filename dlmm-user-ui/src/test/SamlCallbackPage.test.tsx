import { describe, it, expect, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import i18n from '@/i18n'
import SamlCallbackPage from '../pages/SamlCallbackPage'

afterEach(() => {
  i18n.changeLanguage('ru')
})

describe('SamlCallbackPage', () => {
  it('shows the SSO-not-configured state with Russian copy', async () => {
    render(<SamlCallbackPage />)
    expect(await screen.findByText('Корпоративный SSO не настроен')).toBeInTheDocument()
    expect(screen.getByText(/Обратитесь к администратору/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Вернуться ко входу' })).toHaveAttribute('href', '#/login')
  })

  it('renders English copy in en without Cyrillic', async () => {
    await i18n.changeLanguage('en')
    render(<SamlCallbackPage />)
    expect(await screen.findByText('Corporate SSO is not configured')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to sign-in' })).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/[А-Яа-яЁё]/)
  })
})
