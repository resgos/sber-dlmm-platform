import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import KycStatusBadge from '../components/KycStatusBadge'

describe('KycStatusBadge', () => {
  it('shows "Верифицирован" for VERIFIED status', () => {
    render(<KycStatusBadge status="VERIFIED" />)
    expect(screen.getByText('Верифицирован')).toBeInTheDocument()
  })

  it('shows "Ожидание верификации" for PENDING status', () => {
    render(<KycStatusBadge status="PENDING" />)
    expect(screen.getByText('Ожидание верификации')).toBeInTheDocument()
  })

  it('shows "Отклонено" for REJECTED status', () => {
    render(<KycStatusBadge status="REJECTED" />)
    expect(screen.getByText('Отклонено')).toBeInTheDocument()
  })

  it('shows "Не подана" for NOT_SUBMITTED status', () => {
    render(<KycStatusBadge status="NOT_SUBMITTED" />)
    expect(screen.getByText('Не подана')).toBeInTheDocument()
  })

  it('shows "Заблокирован" for BLOCKED status', () => {
    render(<KycStatusBadge status="BLOCKED" />)
    expect(screen.getByText('Заблокирован')).toBeInTheDocument()
  })

  it('falls back to NOT_SUBMITTED for unknown status', () => {
    render(<KycStatusBadge status="UNKNOWN" />)
    expect(screen.getByText('Не подана')).toBeInTheDocument()
  })

  it('renders larger badge when large=true', () => {
    const { container } = render(<KycStatusBadge status="VERIFIED" large />)
    const tag = container.querySelector('.ant-tag')
    expect(tag).toHaveStyle({ fontSize: '14px' })
  })
})
