import { render, screen } from '@testing-library/react'
import { describe, it, expect, afterEach } from 'vitest'
import i18n from '@/i18n'
import RiskDisclosure from '../components/RiskDisclosure'

// The copy is now i18n-driven; reset to the default locale after each test so a
// language switch in one case can't leak into the next.
afterEach(() => { i18n.changeLanguage('ru') })

describe('RiskDisclosure (G-14)', () => {
  it('LP variant calls out impermanent loss + АСВ + past-perf', () => {
    render(<RiskDisclosure variant="lp" />)
    expect(screen.getByText(/Impermanent loss/i)).toBeInTheDocument()
    expect(screen.getByText(/Это не банковский депозит/)).toBeInTheDocument()
    expect(screen.getByText(/АСВ/)).toBeInTheDocument()
    expect(screen.getByText(/будущую/)).toBeInTheDocument()
  })

  it('swap variant calls out slippage + reverse cost', () => {
    render(<RiskDisclosure variant="swap" />)
    expect(screen.getByText(/Проскальзывание/i)).toBeInTheDocument()
    expect(screen.getByText(/Обратный своп/i)).toBeInTheDocument()
  })

  it('hedge variant calls out fixed-rate + opportunity cost + can-close-early', () => {
    render(<RiskDisclosure variant="hedge" />)
    expect(screen.getByText(/Зафиксированный курс/i)).toBeInTheDocument()
    // "Хедж" appears in multiple sentences — assert at least one node
    expect(screen.getAllByText(/Хедж/i).length).toBeGreaterThan(0)
  })

  it('renders as a warning Alert (always-on, never dismissible)', () => {
    const { container } = render(<RiskDisclosure variant="lp" />)
    const alert = container.querySelector('.ant-alert-warning')
    expect(alert).toBeTruthy()
    // No close button — compliance requires always-visible disclosure.
    expect(container.querySelector('.ant-alert-close-icon')).toBeNull()
  })

  it('renders English copy when language is en (bilingual compliance)', async () => {
    await i18n.changeLanguage('en')
    const { container } = render(<RiskDisclosure variant="lp" />)
    expect(container.textContent).toMatch(/Important risk information/)
    expect(container.textContent).toMatch(/This is not a bank deposit/)
    expect(container.textContent).toMatch(/does not cover/)
    // No Russian copy leaks through in EN mode.
    expect(container.textContent).not.toMatch(/банковский депозит/)
  })

  it('swap + hedge variants localise to English too', async () => {
    await i18n.changeLanguage('en')
    const { container: swap } = render(<RiskDisclosure variant="swap" />)
    expect(swap.textContent).toMatch(/Slippage/)
    expect(swap.textContent).toMatch(/reverse swap/i)
    const { container: hedge } = render(<RiskDisclosure variant="hedge" />)
    expect(hedge.textContent).toMatch(/settlement date/)
    expect(hedge.textContent).toMatch(/obligation, not an option/)
  })
})
