// SK-01 v2 — Сберкот celebration bus.
//
// A tiny decoupled channel so any page can make Сберкот pop a celebrate
// pose + a cheerful one-liner on a successful action (swap, add/withdraw
// liquidity, claim) WITHOUT importing the widget or threading callbacks.
// The SberkotAssistant (mounted once in UserLayout) listens for the event.
//
// Kept framework-free (just a CustomEvent on window) so it's trivially
// callable from React Query onSuccess handlers anywhere in the app.

export const SBERKOT_CELEBRATE = 'sberkot:celebrate'

export interface SberkotCelebrateDetail {
  message: string
}

/** Fire-and-forget — tell Сберкот to celebrate with a short message. */
export function celebrateSberkot(message: string): void {
  try {
    window.dispatchEvent(
      new CustomEvent<SberkotCelebrateDetail>(SBERKOT_CELEBRATE, { detail: { message } }),
    )
  } catch {
    /* SSR / no-window — no-op */
  }
}
