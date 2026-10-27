import { describe, it, expect, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18n from '@/i18n'
import TokenSelect from '../components/TokenSelect'

const TOKENS = [
  { id: 'a', symbol: 'SBER', name: 'Сбербанк', available: 1234.5 },
  { id: 'b', symbol: 'SRUB', name: 'Цифровой рубль' },
]

afterEach(() => {
  i18n.changeLanguage('ru')
})

describe('TokenSelect', () => {
  it('renders English picker copy and en-US balance in en', async () => {
    await i18n.changeLanguage('en')
    render(<TokenSelect value="a" onChange={() => {}} tokens={TOKENS} />)
    const trigger = screen.getByRole('button', { name: 'Token SBER' })
    await userEvent.click(trigger)
    expect(await screen.findByText('Select a token')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('Search by ticker or name')).toBeInTheDocument()
    expect(screen.getByText('1,234.5')).toBeInTheDocument()
    await userEvent.type(screen.getByPlaceholderText('Search by ticker or name'), 'zzz')
    expect(screen.getByText('Nothing found')).toBeInTheDocument()
  })

  it('uses the localized default placeholder and follows a live language switch', async () => {
    render(<TokenSelect value={null} onChange={() => {}} tokens={TOKENS} />)
    expect(screen.getByRole('button', { name: 'Выбрать токен' })).toBeInTheDocument()
    await i18n.changeLanguage('en')
    expect(await screen.findByRole('button', { name: 'Select token' })).toBeInTheDocument()
  })
})
