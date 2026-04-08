import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import StrategySelector from '../components/StrategySelector'

describe('StrategySelector', () => {
  it('renders all three strategies', () => {
    render(<StrategySelector value="SPOT" onChange={() => {}} />)
    expect(screen.getByText('Равномерная')).toBeInTheDocument()
    expect(screen.getByText('Концентрированная')).toBeInTheDocument()
    expect(screen.getByText('Двусторонняя')).toBeInTheDocument()
  })

  it('calls onChange when a strategy card is clicked', () => {
    const onChange = vi.fn()
    render(<StrategySelector value="SPOT" onChange={onChange} />)

    fireEvent.click(screen.getByText('Концентрированная'))
    expect(onChange).toHaveBeenCalledWith('CURVE')
  })

  it('calls onChange with BID_ASK when двусторонняя is clicked', () => {
    const onChange = vi.fn()
    render(<StrategySelector value="SPOT" onChange={onChange} />)

    fireEvent.click(screen.getByText('Двусторонняя'))
    expect(onChange).toHaveBeenCalledWith('BID_ASK')
  })

  it('shows strategy descriptions', () => {
    render(<StrategySelector value="CURVE" onChange={() => {}} />)
    expect(screen.getByText(/Равномерное распределение/)).toBeInTheDocument()
    expect(screen.getByText(/Ликвидность сконцентрирована/)).toBeInTheDocument()
    expect(screen.getByText(/Стратегия маркет-мейкера/)).toBeInTheDocument()
  })
})
