import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import i18n from '@/i18n'
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
    expect(screen.queryByRole('img')).toBeNull()
  })

  it('every wired term has a non-empty i18n term + definition (< 400 chars)', () => {
    // Sanity floor — if a term wired into a page loses its copy this fires and
    // forces restoring the locale entry (the copy now lives in i18n, not here).
    const required = ['bin', 'slippage', 'il', 'lp', 'apy', 'tvl', 'basefee', 'binstep', 'pivot', 'rangefit']
    for (const key of required) {
      expect(GLOSSARY_TERMS, `Missing glossary key: ${key}`).toContain(key)
      const term = i18n.t(`glossary.${key}.term`)
      const def = i18n.t(`glossary.${key}.definition`)
      expect(term.length, `Empty term: ${key}`).toBeGreaterThan(0)
      expect(def.length, `Definition for "${key}" is a stub`).toBeGreaterThan(20)
      expect(def.length, `Definition for "${key}" too long`).toBeLessThan(400)
    }
  })

  it('localises term + definition to English', async () => {
    await i18n.changeLanguage('en')
    expect(i18n.t('glossary.il.term')).toMatch(/Impermanent Loss/)
    expect(i18n.t('glossary.bin.definition')).toMatch(/tiny price range/)
    expect(i18n.t('glossary.examplePrefix')).toBe('Example:')
    await i18n.changeLanguage('ru')
  })
})
