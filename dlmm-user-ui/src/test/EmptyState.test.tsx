import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { Button } from 'antd'
import EmptyState from '../components/EmptyState'

describe('EmptyState (UI-CRITIQUE #9)', () => {
  it('renders title + description when provided', () => {
    render(<EmptyState title="Ничего нет" description="Попробуйте создать первое" />)
    expect(screen.getByText('Ничего нет')).toBeInTheDocument()
    expect(screen.getByText('Попробуйте создать первое')).toBeInTheDocument()
  })

  it('renders CTA button if provided', () => {
    render(<EmptyState title="Пусто" cta={<Button>Создать</Button>} />)
    expect(screen.getByRole('button', { name: 'Создать' })).toBeInTheDocument()
  })

  it('renders secondary action alongside CTA', () => {
    render(
      <EmptyState
        title="X"
        cta={<Button>Primary</Button>}
        secondary={<Button type="link">Cancel</Button>}
      />,
    )
    expect(screen.getByRole('button', { name: 'Primary' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
  })

  it('compact size uses smaller padding', () => {
    const { container, rerender } = render(<EmptyState title="X" size="default" />)
    const def = container.querySelector('.ant-empty') as HTMLElement
    const defPad = window.getComputedStyle(def).padding
    rerender(<EmptyState title="X" size="compact" />)
    const compact = container.querySelector('.ant-empty') as HTMLElement
    const compactPad = window.getComputedStyle(compact).padding
    expect(defPad).not.toBe(compactPad)
  })
})
