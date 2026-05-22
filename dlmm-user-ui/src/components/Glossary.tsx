import { Popover, Typography } from 'antd'
import { QuestionCircleOutlined } from '@ant-design/icons'
import type { ReactNode } from 'react'

const { Text, Paragraph } = Typography

/**
 * Sprint 12 G-18 — plain-language glossary.
 *
 * Wraps a term (bin / slippage / IL / LP / APY / TVL / etc) so first-time
 * users can tap and see a 2-3 sentence explanation in plain Russian.
 *
 * Anna-driven (small-business demo): "Я не понимаю ничего из этих слов"
 * was the most common pushback. The HealthScoreExplainer onboarding
 * banner explains the score; this component explains the vocabulary
 * inline, on-demand.
 *
 * Implementation: pure-frontend, no API. Popover triggered by click
 * (touch-friendly) AND hover (mouse). Definitions live in this module
 * so they version-control with the UI; future Sprint 12+ can swap to a
 * CMS-backed content source if marketing wants editorial control.
 */

interface GlossaryDefinition {
  /** Headline — the term as the user sees it. */
  term: string
  /** 2-3 sentences in plain Russian. Markdown not supported — keep it
   *  simple. The Popover renders a small Paragraph. */
  definition: string
  /** Optional concrete example to anchor abstraction. */
  example?: string
}

const TERMS: Record<string, GlossaryDefinition> = {
  bin: {
    term: 'Бин',
    definition: 'Бин — это маленький диапазон цены внутри пула. У каждого пула много бинов, и в каждом «лежит» своя ликвидность. Когда люди торгуют, проходят через активный бин.',
    example: 'Если SBER стоит 500 ₽, активный бин может покрывать диапазон 499.95–500.05 ₽.',
  },
  slippage: {
    term: 'Проскальзывание (slippage)',
    definition: 'Проскальзывание — разница между ожидаемой ценой и фактической ценой исполнения. Чем больше сумма и меньше ликвидности в пуле, тем больше проскальзывание.',
    example: 'Допуск 0.5% значит «согласен на разницу до 0.5%, иначе отменить сделку».',
  },
  il: {
    term: 'Impermanent Loss (IL)',
    definition: 'Если вы предоставили ликвидность в пул, и цена одного из токенов изменилась, ваша доля может быть менее ценной, чем если бы вы просто хранили токены. Это и есть IL.',
    example: 'Положили 1000 ₽ и 2 SBER (по 500 ₽). SBER вырос до 600 ₽. У вас стало меньше SBER, больше ₽. Если бы просто держали — заработали бы больше.',
  },
  lp: {
    term: 'LP — поставщик ликвидности',
    definition: 'LP (Liquidity Provider) — это вы, когда добавляете ликвидность в пул. За это получаете долю от комиссий каждого свопа, проходящего через пул.',
    example: 'Положили 1М ₽ в SRUB/SBER пул — вы LP. Ваша доля комиссий = 1М ÷ общий TVL × объём торговли × фи.',
  },
  apy: {
    term: 'APY — годовая доходность',
    definition: 'APY (Annual Percentage Yield) — оценка вашего годового дохода, если текущая активность пула сохранится 12 месяцев. Не гарантия.',
    example: '«APY 18%» = если ничего не поменяется, на каждые 1 000 000 ₽ заработаете ~180 000 ₽ за год.',
  },
  tvl: {
    term: 'TVL — общая ликвидность',
    definition: 'TVL (Total Value Locked) — суммарная стоимость всего что лежит в пуле. Чем больше TVL, тем стабильнее цена при крупных сделках.',
  },
  basefee: {
    term: 'Базовая комиссия',
    definition: 'Доля от каждой сделки в пуле, которую платит трейдер. Обычно 0.05–1%. Делится между LP и протоколом (Sber берёт 5% от этой комиссии).',
  },
  binstep: {
    term: 'Шаг бина',
    definition: 'Расстояние между соседними бинами в % от цены. Меньший шаг = точнее размещение ликвидности, но дороже её поддерживать.',
    example: 'Шаг 0.25% значит соседний бин отстоит от текущей цены на 0.25%.',
  },
  pivot: {
    term: 'Pivot-валюта',
    definition: 'Сейчас в платформе SRUB — pivot-валюта. Любой обмен X↔Y проходит через SRUB: сначала X→SRUB, потом SRUB→Y. Так платформа использует ликвидность одного места.',
  },
  rangefit: {
    term: 'Соответствие диапазону',
    definition: 'Если активный бин пула попадает в выбранный вами диапазон — ваша ликвидность работает и зарабатывает. Если вышел — позиция простаивает.',
  },
}

interface GlossaryProps {
  /** Key from TERMS map. Unknown keys render the children без popover'а. */
  term: string
  children: ReactNode
}

/**
 * Inline-wrap a piece of text with a glossary tooltip. Renders the
 * children + a small ⓘ icon that the user can tap.
 */
export default function Glossary({ term, children }: GlossaryProps) {
  const def = TERMS[term]
  if (!def) {
    // Unknown term — fail open, render children как есть. Каскад
    // нечасто, но логируем чтобы поймать опечатки в dev.
    if (typeof window !== 'undefined' && process.env.NODE_ENV !== 'production') {
      console.warn(`[Glossary] unknown term "${term}"`)
    }
    return <>{children}</>
  }

  return (
    <Popover
      trigger={['click', 'hover']}
      placement="top"
      content={
        <div style={{ maxWidth: 320 }}>
          <Text strong style={{ display: 'block', marginBottom: 6 }}>{def.term}</Text>
          <Paragraph style={{ fontSize: 13, marginBottom: def.example ? 8 : 0 }}>
            {def.definition}
          </Paragraph>
          {def.example && (
            <Text type="secondary" style={{ fontSize: 11, fontStyle: 'italic', display: 'block' }}>
              Пример: {def.example}
            </Text>
          )}
        </div>
      }
    >
      <span style={{ borderBottom: '1px dotted var(--text-muted)', cursor: 'help' }}>
        {children}
        <QuestionCircleOutlined style={{ fontSize: 11, marginInlineStart: 4, color: 'var(--text-muted)' }} />
      </span>
    </Popover>
  )
}

/** Exported for tests + future content audits. */
export { TERMS as GLOSSARY_TERMS }
