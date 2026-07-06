import { describe, it, expect } from 'vitest'
import { compactNotificationAmounts } from '../lib/notificationText'

// 2026-07-06 — render-time compaction of backend notification copy. The
// fixture is the REAL message produced by the live canary swap.
describe('compactNotificationAmounts', () => {
  it('compacts the grouped ledger-precision amount from a real swap message', () => {
    expect(compactNotificationAmounts('Своп выполнен: 1 SBTC → 4 866 041,7865 SRUB'))
      .toBe('Своп выполнен: 1 SBTC → 4.87 млн SRUB')
  })

  it('leaves small counts, rates and dates untouched', () => {
    expect(compactNotificationAmounts('Позиция 1 SBTC, ставка 25 bps, 06.07.2026 00:31'))
      .toBe('Позиция 1 SBTC, ставка 25 bps, 06.07.2026 00:31')
  })

  it('leaves grouped numbers under 10k untouched', () => {
    expect(compactNotificationAmounts('Комиссия 9 999,99 SRUB')).toBe('Комиссия 9 999,99 SRUB')
  })

  it('handles NBSP group separators and multiple amounts', () => {
    expect(compactNotificationAmounts('Внесено 12 500 000 SRUB и 250 000 SUSDT'))
      .toBe('Внесено 12.50 млн SRUB и 250.0 тыс SUSDT')
  })

  it('null/undefined → empty string', () => {
    expect(compactNotificationAmounts(null)).toBe('')
    expect(compactNotificationAmounts(undefined)).toBe('')
  })
})
