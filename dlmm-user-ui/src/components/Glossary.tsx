import { Popover, Typography } from 'antd'
import { QuestionCircleOutlined } from '@ant-design/icons'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

const { Text, Paragraph } = Typography

/**
 * Sprint 12 G-18 — plain-language glossary.
 *
 * Wraps a term (bin / slippage / IL / LP / APY / TVL / etc) so first-time
 * users can tap and see a 2-3 sentence explanation. Popover triggered by click
 * (touch-friendly) AND hover (mouse).
 *
 * Anna-driven (small-business demo): "Я не понимаю ничего из этих слов" was the
 * most common pushback. HealthScoreExplainer explains the score; this component
 * explains the vocabulary inline, on-demand.
 *
 * i18n (2026-06-17): the term/definition/example copy lives in i18n under
 * `glossary.<key>.*` (ru+en) so the tooltips are bilingual — an EN investor no
 * longer sees Russian definitions. This module keeps only the *registry* of
 * known keys; the wording is editorial content in the locale bundles.
 */

// The set of known glossary terms. Copy lives in i18n (glossary.<key>.term /
// .definition / .example). Exported for tests + content audits.
export const GLOSSARY_TERMS = [
  'bin', 'slippage', 'il', 'lp', 'apy', 'tvl', 'basefee', 'binstep', 'pivot', 'rangefit',
] as const

interface GlossaryProps {
  /** Key from {@link GLOSSARY_TERMS}. Unknown keys render the children without a popover. */
  term: string
  children: ReactNode
}

/**
 * Inline-wrap a piece of text with a glossary tooltip. Renders the children +
 * a small ⓘ icon that the user can tap.
 */
export default function Glossary({ term, children }: GlossaryProps) {
  const { t } = useTranslation()
  const known = (GLOSSARY_TERMS as readonly string[]).includes(term)
  if (!known) {
    // Unknown term — fail open, render children as-is. Rare, but logged in dev
    // to catch typos in page-side usage.
    if (typeof window !== 'undefined' && import.meta.env.DEV) {
      console.warn(`[Glossary] unknown term "${term}"`)
    }
    return <>{children}</>
  }

  // Not every term has an example; an absent key resolves to '' (falsy).
  const example = t(`glossary.${term}.example`, { defaultValue: '' })

  return (
    <Popover
      trigger={['click', 'hover']}
      placement="top"
      content={
        <div style={{ maxWidth: 320 }}>
          <Text strong style={{ display: 'block', marginBottom: 6 }}>{t(`glossary.${term}.term`)}</Text>
          <Paragraph style={{ fontSize: 'var(--text-sm)', marginBottom: example ? 8 : 0 }}>
            {t(`glossary.${term}.definition`)}
          </Paragraph>
          {example && (
            <Text type="secondary" style={{ fontSize: 'var(--text-xs)', fontStyle: 'italic', display: 'block' }}>
              {t('glossary.examplePrefix')} {example}
            </Text>
          )}
        </div>
      }
    >
      <span style={{ borderBottom: '1px dotted var(--text-muted)', cursor: 'help' }}>
        {children}
        <QuestionCircleOutlined style={{ fontSize: 'var(--text-xs)', marginInlineStart: 4, color: 'var(--text-muted)' }} />
      </span>
    </Popover>
  )
}
