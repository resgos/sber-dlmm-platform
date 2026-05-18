import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import TokenChip, { pairAccent } from '@/components/TokenChip'

describe('pairAccent', () => {
  it('returns one of 8 palette entries', () => {
    const result = pairAccent('SBER')
    expect(result).toHaveProperty('from')
    expect(result).toHaveProperty('to')
    expect(result.from).toMatch(/^#[0-9A-Fa-f]{6}$/)
    expect(result.to).toMatch(/^#[0-9A-Fa-f]{6}$/)
  })

  it('is deterministic — same symbol always returns same colours', () => {
    const a = pairAccent('SBER')
    const b = pairAccent('SBER')
    expect(a).toEqual(b)
  })

  it('different symbols can return different colours (hash spread)', () => {
    // We don't assert they're always different (palette has 8 entries → collisions
    // possible for similar hashes), but at least some pair should differ across
    // typical seed symbols.
    const symbols = ['SBER', 'GAZP', 'LKOH', 'YNDX', 'GMKN', 'ROSN', 'MGNT', 'VTBR']
    const accents = new Set(symbols.map((s) => pairAccent(s).from))
    expect(accents.size).toBeGreaterThan(1)
  })

  it('empty string still returns a valid pair (defensive)', () => {
    const result = pairAccent('')
    expect(result.from).toMatch(/^#[0-9A-Fa-f]{6}$/)
  })
})

describe('TokenChip', () => {
  it('renders symbol slice (first 4 chars) for given symbol', () => {
    render(<TokenChip symbol="SBERBANK" />)
    expect(screen.getByText('SBER')).toBeInTheDocument()
  })

  it('renders em-dash for missing symbol', () => {
    render(<TokenChip />)
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('has aria-label for accessibility (Sprint 7 #C-1 mitigation)', () => {
    render(<TokenChip symbol="SUSD" />)
    expect(screen.getByLabelText('Токен SUSD')).toBeInTheDocument()
  })

  it('missing symbol has aria-label "Токен не выбран"', () => {
    render(<TokenChip />)
    expect(screen.getByLabelText('Токен не выбран')).toBeInTheDocument()
  })

  it('size prop overrides default 32px', () => {
    const { container } = render(<TokenChip symbol="X" size={48} />)
    const chip = container.firstChild as HTMLElement
    expect(chip.style.width).toBe('48px')
    expect(chip.style.height).toBe('48px')
  })
})
