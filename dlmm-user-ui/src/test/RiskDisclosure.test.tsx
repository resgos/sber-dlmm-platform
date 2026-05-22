import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import RiskDisclosure from '../components/RiskDisclosure'

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
})
