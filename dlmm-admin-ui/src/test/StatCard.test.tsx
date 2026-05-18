import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import StatCard, { formatRub } from '../components/StatCard'
import { WalletOutlined } from '@ant-design/icons'

/**
 * Sprint 8 C-7 — first admin-ui test file. StatCard is the shared
 * component extracted in Sprint 7 (commit 1e318e1) — testing it here
 * pins the admin-side render contract, distinct from user-ui's StatCard
 * which has a different prop signature (string|number value vs number-only).
 */
describe('StatCard (admin-ui)', () => {
  it('renders title and ru-RU-formatted numeric value', () => {
    render(
      <StatCard
        title="Всего пользователей"
        value={1234}
        icon={<WalletOutlined />}
        iconBg="#FEF3C7"
        iconColor="#F59E0B"
      />,
    )
    expect(screen.getByText('Всего пользователей')).toBeInTheDocument()
    // ru-RU uses NBSP as thousand separator
    expect(screen.getByText(/1[\s ]?234/)).toBeInTheDocument()
  })

  it('uses formatter when provided (formatRub)', () => {
    render(
      <StatCard
        title="TVL"
        value={5_000_000}
        icon={<WalletOutlined />}
        iconBg="#fff"
        iconColor="#000"
        formatter={formatRub}
      />,
    )
    // 5_000_000 → "5.00 млн ₽"
    expect(screen.getByText(/5\.00\s*млн\s*₽/)).toBeInTheDocument()
  })

  it('decorative icon container is aria-hidden', () => {
    // Audit C-1 — accessibility: stat-tile decorative icons must not
    // pollute screen-reader output. The aria-hidden was added during
    // the Sprint 7 extraction.
    const { container } = render(
      <StatCard
        title="X"
        value={1}
        icon={<WalletOutlined data-testid="stat-icon" />}
        iconBg="#fff"
        iconColor="#000"
      />,
    )
    const iconWrapper = container.querySelector('[aria-hidden]')
    expect(iconWrapper).toBeInTheDocument()
  })
})

describe('formatRub (admin-ui)', () => {
  it('formats billions with 2 decimals', () => {
    expect(formatRub(2_500_000_000)).toBe('2.50 млрд ₽')
  })

  it('formats millions with 2 decimals', () => {
    expect(formatRub(12_345_000)).toBe('12.35 млн ₽')
  })

  it('formats thousands with 1 decimal', () => {
    expect(formatRub(42_000)).toBe('42.0 тыс ₽')
  })

  it('formats sub-thousand values as raw + ₽ suffix', () => {
    const result = formatRub(999)
    expect(result).toContain('₽')
    expect(result).toContain('999')
    // Must NOT have "тыс" / "млн" / "млрд" buckets for sub-1k values
    expect(result).not.toMatch(/тыс|млн|млрд/)
  })

  it('handles zero', () => {
    const result = formatRub(0)
    expect(result).toContain('0')
    expect(result).toContain('₽')
  })
})
