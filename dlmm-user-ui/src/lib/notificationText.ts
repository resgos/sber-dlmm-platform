import { formatCompact } from './format'

/**
 * 2026-07-06 — compact the raw amounts inside backend-composed notification
 * copy at RENDER time.
 *
 * The backend writes ledger-precision figures into the message text
 * («Своп выполнен: 1 SBTC → 4 866 041,7865 SRUB») — right for the ledger,
 * noise in a feed/push. We don't touch the backend copy (that's the
 * bilingual-templates project); instead, grouped-thousands numbers ≥ 10k are
 * re-rendered through the shared formatCompact (locale-aware: «4.87 млн» /
 * "4.87M") when the notification is displayed.
 *
 * Only numbers WITH digit grouping (space/NBSP every 3 digits, optional
 * decimal comma) are candidates — small counts («1 SBTC»), rates («25 bps»),
 * dates and times never match the pattern.
 */
const GROUPED_NUMBER = /\d{1,3}(?:[  ]\d{3})+(?:,\d+)?/g

export function compactNotificationAmounts(message: string | null | undefined): string {
  if (!message) return ''
  return message.replace(GROUPED_NUMBER, (match) => {
    const value = Number(match.replace(/[  ]/g, '').replace(',', '.'))
    if (!Number.isFinite(value) || Math.abs(value) < 10_000) return match
    return formatCompact(value)
  })
}
