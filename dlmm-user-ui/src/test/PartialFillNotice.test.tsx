import { describe, it, expect, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import i18n from '@/i18n'
import PartialFillNotice from '../components/PartialFillNotice'

afterEach(() => {
  i18n.changeLanguage('ru')
})

describe('PartialFillNotice', () => {
  it('renders nothing on a full fill', () => {
    const { container } = render(<PartialFillNotice fillable={100} requested={100} symbol="SGLD" />)
    expect(container).toBeEmptyDOMElement()
  })

  it('warns in Russian with both amounts interpolated', () => {
    render(<PartialFillNotice fillable={12.5} requested={40} symbol="SGLD" />)
    expect(screen.getByText('Ограниченная ликвидность пула')).toBeInTheDocument()
    const desc = screen.getByText(/В пул вмещается только/)
    expect(desc.textContent).toMatch(/12[.,]5 SGLD из 40 SGLD/)
  })

  it('renders English copy in en', async () => {
    await i18n.changeLanguage('en')
    render(<PartialFillNotice fillable={12.5} requested={40} symbol="SGLD" />)
    expect(screen.getByText('Limited pool liquidity')).toBeInTheDocument()
    const desc = screen.getByText(/The pool can only take/)
    expect(desc.textContent).toMatch(/12\.5 SGLD of 40 SGLD/)
    expect(desc.textContent).not.toMatch(/[А-Яа-яЁё]/)
  })
})
