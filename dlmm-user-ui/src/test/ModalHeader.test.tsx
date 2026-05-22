import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import ModalHeader from '../components/ModalHeader'

describe('ModalHeader (UI-CRITIQUE #12)', () => {
  it('renders title text', () => {
    render(<ModalHeader title="Удаление" severity="danger" />)
    expect(screen.getByText('Удаление')).toBeInTheDocument()
  })

  it('different severities use different icons (by colour token)', () => {
    const { container, rerender } = render(<ModalHeader title="t" severity="info" />)
    const infoColor = (container.querySelector('span[style*="color"]') as HTMLElement).style.color
    rerender(<ModalHeader title="t" severity="danger" />)
    const dangerColor = (container.querySelector('span[style*="color"]') as HTMLElement).style.color
    expect(infoColor).not.toBe(dangerColor)
  })

  it('custom icon overrides the severity default', () => {
    const customIcon = <span data-testid="custom-icon">★</span>
    render(<ModalHeader title="X" severity="info" icon={customIcon} />)
    expect(screen.getByTestId('custom-icon')).toBeInTheDocument()
  })
})
