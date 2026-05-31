import type { TokenPrice } from '@/api/types'

/**
 * SK-03 — Сберкот interactivity content (pure, unit-tested in
 * src/test/sberkotQuips.test.ts). Drives the "pet the cat" reactions: a rotating
 * pool of playful tips, milestone messages, and a LIVE market one-liner built
 * from the real oracle prices (CoinGecko / ЦБ РФ) so the cat actually reacts to
 * the market it now tracks.
 */

/** Playful one-liners shown when you pet Сберкот. */
export const PET_QUIPS: string[] = [
  'Мур-р-р… 🐾 спасибо, что погладил!',
  'Я слежу за рынком, чтобы ты не пропустил выгодный момент.',
  'Совет: пул с высоким объёмом обычно даёт больше комиссий поставщику ликвидности.',
  'DLMM раскладывает ликвидность по «бинам» — узким ценовым корзинам. Внутри бина проскальзывание почти ноль.',
  'Не клади все средства в один бин — шире диапазон, спокойнее сон 😴',
  'Я в зелёной толстовке, потому что это цвет Сбера! 💚',
  'Биржевые цены на Главной обновляются вживую: крипта — CoinGecko, валюты и металлы — ЦБ РФ.',
  'Комиссии капают, только пока цена внутри твоего диапазона — следи за «Здоровьем» позиции.',
  'Позиция вышла из диапазона? Жми «Ребаланс» — перенесу её к текущей цене.',
  'Теперь можно покупать дробно — хоть 0,5 биткоина 🪙',
  'Цена внутри пула теперь тянется за биржей — я подгоняю её каждую минуту 🐾',
]

/** Special messages at pet-count milestones (else null). */
export const PET_MILESTONES: Record<number, string> = {
  5: 'Мы подружились! 🐾💚',
  10: 'Ты — мой любимый трейдер! 🥰',
  25: '25 поглаживаний! Ну ты даёшь 🎉',
  50: 'Полтинник! Теперь я официально твой питомец 🐈',
  100: '100 раз! Ты — легенда Сберкота 🏆',
}

export function milestoneMessage(petCount: number): string | null {
  return PET_MILESTONES[petCount] ?? null
}

/** Display names for the real-priced assets the cat may comment on. */
const ASSET_NAMES: Record<string, string> = {
  SBTC: 'Биткоин',
  SETH: 'Эфир',
  SGOLD: 'Золото',
  SSILV: 'Серебро',
  SPLAT: 'Платина',
  SPALD: 'Палладий',
  SUSDT: 'Доллар',
  SEUR: 'Евро',
  SCNY: 'Юань',
}

/**
 * A one-liner about the biggest 24h mover among the real-priced assets, or null
 * if no usable price is loaded yet. Deterministic given the input.
 */
export function marketMoodLine(prices: TokenPrice[] | undefined): string | null {
  const real = (prices ?? []).filter(
    (p) => ASSET_NAMES[p.symbol] && p.price > 0 && Number.isFinite(p.change24h),
  )
  if (real.length === 0) return null
  const top = real.reduce((a, b) => (Math.abs(b.change24h) > Math.abs(a.change24h) ? b : a))
  const name = ASSET_NAMES[top.symbol]
  const dir = top.change24h >= 0 ? '📈' : '📉'
  const sign = top.change24h >= 0 ? '+' : ''
  const px = Math.round(top.price).toLocaleString('ru-RU')
  return `${name} сегодня ${sign}${top.change24h.toFixed(2)}% ${dir} (${px} ₽). Я слежу за рынком 🐾`
}

/** Pick a pet reaction. Every 3rd pet tries the live market line, else a quip. */
export function petReaction(
  petCount: number,
  prices: TokenPrice[] | undefined,
  rand: () => number = Math.random,
): string {
  const milestone = milestoneMessage(petCount)
  if (milestone) return milestone
  if (petCount % 3 === 0) {
    const market = marketMoodLine(prices)
    if (market) return market
  }
  return PET_QUIPS[Math.floor(rand() * PET_QUIPS.length)] ?? PET_QUIPS[0]
}
