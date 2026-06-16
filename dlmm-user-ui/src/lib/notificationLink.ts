import type { Notification } from '@/api/types'

const UUID_RE = /([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/i

/**
 * The in-app destination a notification should open when clicked, or null when
 * it's purely informational (no useful target).
 *
 * Centralises what used to be a margin-alert-only deep-link inlined in
 * NotificationBell, so the header bell and the full /notifications page behave
 * identically — and more notification types become actionable instead of just
 * the two margin ones. Margin alerts still extract the position UUID from the
 * message and deep-link with ?highlight=, falling back to the plain list.
 */
export function notificationDeepLink(n: Pick<Notification, 'type' | 'message'>): string | null {
  switch (n.type) {
    case 'MARGIN_WARNING':
    case 'MARGIN_CALL': {
      const m = n.message?.match(UUID_RE)
      return m ? `/positions?highlight=${m[1]}` : '/positions'
    }
    case 'POSITION_CLOSED':
    case 'LIQUIDITY_ADDED':
    case 'FEE_ACCRUED':
      return '/positions'
    case 'SWAP_COMPLETED':
      return '/transactions'
    case 'KYC_APPROVED':
    case 'KYC_REJECTED':
      return '/profile'
    case 'POOL_PAUSED':
    case 'POOL_UPDATE':
      return '/pools'
    default:
      // SYSTEM_ALERT and any unknown/future type: informational, no navigation.
      return null
  }
}
