import { describe, it, expect } from 'vitest'
import { notificationDeepLink } from '@/lib/notificationLink'
import type { NotificationType } from '@/api/types'

const n = (type: NotificationType, message = '') => ({ type, message })

describe('notificationDeepLink', () => {
  it('deep-links margin alerts to the position by UUID from the message', () => {
    const id = 'a0000000-0000-0000-0000-000000000002'
    expect(notificationDeepLink(n('MARGIN_WARNING', `Позиция ${id} под угрозой`)))
      .toBe(`/positions?highlight=${id}`)
    expect(notificationDeepLink(n('MARGIN_CALL', `margin call for ${id}`)))
      .toBe(`/positions?highlight=${id}`)
  })

  it('falls back to the plain positions list when no UUID is present', () => {
    expect(notificationDeepLink(n('MARGIN_WARNING', 'нет id'))).toBe('/positions')
  })

  it('routes fee / liquidity / position notifications to /positions', () => {
    expect(notificationDeepLink(n('FEE_ACCRUED'))).toBe('/positions')
    expect(notificationDeepLink(n('LIQUIDITY_ADDED'))).toBe('/positions')
    expect(notificationDeepLink(n('POSITION_CLOSED'))).toBe('/positions')
  })

  it('routes swaps to /transactions, KYC to /profile, pools to /pools', () => {
    expect(notificationDeepLink(n('SWAP_COMPLETED'))).toBe('/transactions')
    expect(notificationDeepLink(n('KYC_APPROVED'))).toBe('/profile')
    expect(notificationDeepLink(n('KYC_REJECTED'))).toBe('/profile')
    expect(notificationDeepLink(n('POOL_PAUSED'))).toBe('/pools')
    expect(notificationDeepLink(n('POOL_UPDATE'))).toBe('/pools')
  })

  it('returns null for purely informational types', () => {
    expect(notificationDeepLink(n('SYSTEM_ALERT'))).toBeNull()
  })
})
