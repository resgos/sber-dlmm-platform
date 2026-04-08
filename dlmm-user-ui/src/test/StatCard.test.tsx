import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import StatCard, { formatRub } from '../components/StatCard'
import { WalletOutlined } from '@ant-design/icons'

describe('StatCard', () => {
  it('renders title and numeric value', () => {
    render(
      <StatCard
        title="Общий баланс"
        value={1234}
        icon={<WalletOutlined />}
        iconBg="#FEF3C7"
        iconColor="#F59E0B"
      />,
    )
    expect(screen.getByText('Общий баланс')).toBeInTheDocument()
    // Value is formatted with ru-RU locale
    expect(screen.getByText(/1[\s\u00a0]?234/)).toBeInTheDocument()
  })

  it('renders string value directly', () => {
    render(
      <StatCard title="APY" value="12.5%" icon={<WalletOutlined />} iconBg="#fff" iconColor="#000" />,
    )
    expect(screen.getByText('12.5%')).toBeInTheDocument()
  })

  it('uses formatter when provided', () => {
    render(
      <StatCard
        title="TVL"
        value={5000000}
        icon={<WalletOutlined />}
        iconBg="#fff"
        iconColor="#000"
        formatter={formatRub}
      />,
    )
    expect(screen.getByText(/5.*млн.*₽/)).toBeInTheDocument()
  })
})

describe('formatRub', () => {
  it('formats billions', () => {
    expect(formatRub(2_500_000_000)).toBe('2.50 млрд ₽')
  })

  it('formats millions', () => {
    expect(formatRub(12_345_000)).toBe('12.35 млн ₽')
  })

  it('formats thousands', () => {
    expect(formatRub(42_000)).toBe('42.0 тыс ₽')
  })

  it('formats small values', () => {
    const result = formatRub(999)
    expect(result).toContain('₽')
    expect(result).toContain('999')
  })

  it('formats zero', () => {
    expect(formatRub(0)).toContain('0')
    expect(formatRub(0)).toContain('₽')
  })
})
