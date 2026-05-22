import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import Glossary, { GLOSSARY_TERMS } from '../components/Glossary'

describe('Glossary (G-18)', () => {
  it('renders children + ⓘ icon for known term', () => {
    render(<Glossary term="bin">бин</Glossary>)
    expect(screen.getByText('бин')).toBeInTheDocument()
    // ⓘ icon — AntD renders as <span> with role=img
    expect(screen.getAllByRole('img').length).toBeGreaterThan(0)
  })

  it('renders children unchanged for unknown term (fail-open)', () => {
    render(<Glossary term="zzz-unknown">текст</Glossary>)
    expect(screen.getByText('текст')).toBeInTheDocument()
    // No popover rendered → no AntD popover trigger spans with cursor:help
    // Easiest assertion: no icon
    expect(screen.queryByRole('img')).toBeNull()
  })

  it('has definitions for all the terms used in the codebase', () => {
    // Sanity floor — if someone deletes a term that's wired into a page,
    // this test fires and forces them to either restore the term or
    // sweep the page-side usage.
    const required = ['bin', 'slippage', 'il', 'lp', 'apy', 'tvl', 'basefee', 'binstep', 'pivot', 'rangefit']
    for (const t of required) {
      expect(GLOSSARY_TERMS[t], `Missing glossary term: ${t}`).toBeDefined()
      expect(GLOSSARY_TERMS[t].term.length).toBeGreaterThan(0)
      expect(GLOSSARY_TERMS[t].definition.length).toBeGreaterThan(20) // not a stub
    }
  })

  it('definitions stay under 400 chars (tooltip-fit)', () => {
    for (const [key, def] of Object.entries(GLOSSARY_TERMS)) {
      expect(def.definition.length, `Definition for "${key}" too long`).toBeLessThan(400)
    }
  })
})
