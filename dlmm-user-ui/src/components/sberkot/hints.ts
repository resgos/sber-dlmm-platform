import type { SberkotPose } from './SberkotMascot'

/**
 * SK-01 — Сберкот contextual-hint resolution. Kept as a pure module
 * (no React / no DOM) so the route→hint matching + precedence is unit-tested
 * independently of the widget. See src/test/sberkotHints.test.ts.
 */
export interface Hint {
  key: string
  pose: SberkotPose
  title: string
  text: string
}

/**
 * Ordered MOST-SPECIFIC FIRST — resolveHint returns the first match, so
 * `/pools/<id>/liquidity` must precede `/pools/<id>` must precede `/pools`.
 * Reordering this array changes behaviour; the test pins the precedence.
 */
export const HINTS: Array<{ match: (p: string) => boolean } & Hint> = [
  {
    key: 'liquidity', match: (p) => /^\/pools\/[^/]+\/liquidity/.test(p),
    pose: 'point', title: 'Добавление ликвидности',
    text: 'Выберите стратегию и диапазон бинов — справа сразу видно долю TVL и прогноз комиссий. В Simple-режиме это шаг пропускается: ликвидность добавляется по базовым настройкам.',
  },
  {
    key: 'poolDetail', match: (p) => /^\/pools\/[^/]+/.test(p),
    pose: 'point', title: 'Карточка пула',
    text: 'Здесь реальный график цены пула и стакан по бинам. Клик по строке стакана подставит цену в обмен или лимитку.',
  },
  {
    key: 'pools', match: (p) => p.startsWith('/pools'),
    pose: 'idle', title: 'Пулы ликвидности',
    text: 'Сортируйте по объёму, TVL или APY. Нажмите на пул, чтобы открыть график и добавить ликвидность.',
  },
  {
    key: 'swap', match: (p) => p.startsWith('/swap'),
    pose: 'point', title: 'Обмен',
    text: 'Выберите пару и сумму — комиссия и влияние на цену считаются автоматически. Внутри активного бина проскальзывание = 0%.',
  },
  {
    key: 'positions', match: (p) => p.startsWith('/positions'),
    pose: 'idle', title: 'Мои позиции',
    text: 'Бейдж «Здоровье» показывает, в диапазоне ли позиция. Накопленные комиссии забираются кнопкой «Забрать».',
  },
  {
    key: 'hedge', match: (p) => p.startsWith('/hedge'),
    pose: 'idle', title: 'FX-хедж',
    text: 'Инструмент для валютных рисков казначейства. Перед сделкой откройте дисклеймер рисков.',
  },
  {
    key: 'profile', match: (p) => p.startsWith('/profile'),
    pose: 'idle', title: 'Профиль',
    text: 'Во вкладке «Настройки» — тема оформления, 2FA и переключатель Simple / Pro.',
  },
  {
    key: 'home', match: (p) => p === '/' || p === '',
    pose: 'greet', title: 'Привет! Я Сберкот 🐾',
    text: 'Помогу освоиться. Здесь ваш портфель и быстрые действия. Я буду подсказывать на каждом экране — нажмите на меня в любой момент.',
  },
]

export const DEFAULT_HINT: Hint = {
  key: 'default', pose: 'idle', title: 'Подсказки рядом',
  text: 'Нажмите на меня, если что-то непонятно — подскажу по текущему экрану.',
}

/** Resolve the hint for a pathname; falls back to DEFAULT_HINT. */
export function resolveHint(pathname: string): Hint {
  return HINTS.find((h) => h.match(pathname)) ?? DEFAULT_HINT
}

/** localStorage keys (also pure so tests can assert them). */
export const OFF_KEY = 'sberkot:off'
export const seenKey = (k: string) => `sberkot:seen:${k}`
