import { describe, it, expect, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import i18n from '@/i18n'
import DemoBranding from '@/components/DemoBranding'

function renderAt(url: string) {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <DemoBranding />
    </MemoryRouter>,
  )
}

afterEach(async () => {
  localStorage.removeItem('dlmm.demo.company')
  await i18n.changeLanguage('ru')
})

describe('DemoBranding', () => {
  it('renders nothing without a company param', () => {
    const { container } = renderAt('/')
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the personalised tag in Russian and persists the company', async () => {
    renderAt('/?company=Alpha%20Treasury')
    expect(await screen.findByText('Демо для: Alpha Treasury')).toBeInTheDocument()
    expect(localStorage.getItem('dlmm.demo.company')).toBe('Alpha Treasury')
  })

  it('shows the tag in English when the UI language is EN', async () => {
    await i18n.changeLanguage('en')
    localStorage.setItem('dlmm.demo.company', 'Alpha Treasury')
    renderAt('/')
    expect(await screen.findByText('Demo for: Alpha Treasury')).toBeInTheDocument()
  })
})
