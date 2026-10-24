import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, afterEach } from 'vitest'
import i18n from '@/i18n'
import { themeStore } from '@/store/themeStore'
import ThemeToggle from '../components/ThemeToggle'

afterEach(() => {
  i18n.changeLanguage('ru')
  themeStore.setMode('system')
})

describe('ThemeToggle i18n', () => {
  it('renders Russian copy by default', () => {
    render(<ThemeToggle />)
    expect(screen.getByText('Внешний вид')).toBeInTheDocument()
    expect(screen.getByText('Тема интерфейса')).toBeInTheDocument()
    expect(screen.getByText('Светлая')).toBeInTheDocument()
    expect(screen.getByText('Тёмная')).toBeInTheDocument()
    expect(screen.getByText('Системная')).toBeInTheDocument()
  })

  it('renders English copy with no Cyrillic in en', async () => {
    await i18n.changeLanguage('en')
    const { container } = render(<ThemeToggle />)
    expect(screen.getByText('Appearance')).toBeInTheDocument()
    expect(screen.getByText('Light')).toBeInTheDocument()
    expect(screen.getByText('Dark')).toBeInTheDocument()
    expect(screen.getByText('System')).toBeInTheDocument()
    expect(container.textContent).not.toMatch(/[А-Яа-яЁё]/)
  })

  it('switches the theme store on click', () => {
    render(<ThemeToggle />)
    fireEvent.click(screen.getByText('Тёмная'))
    expect(themeStore.getMode()).toBe('dark')
  })
})
