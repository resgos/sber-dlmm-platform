import { useEffect } from 'react'
import { notification as antdNotification } from 'antd'
import {
  positionAlertsStore,
  alertHistoryStore,
  isOnCooldown,
  type PositionAlert,
} from '@/store/positionAlertsStore'
import type { Position, Pool } from '@/api/types'

// Sprint 10 (new feature) — alert watcher hook.
// Sprint 10 wave 3 polish — when browser permission is denied (or
// unavailable), fall back to AntD `notification.warning` so the user
// still gets a visible alert instead of a silent console.warn. Every
// fire is recorded into `alertHistoryStore` so the drawer can show
// "what fired and when" across all rules, not just per-rule.
//
// Mounted on PositionsPage. On every render (or every React Query
// stale-time refresh, whichever comes first) walks the user's active
// rules, evaluates them against the just-fetched positions+pools, and
// fires a browser Notification if the rule triggers + cooldown has
// passed.
//
// Notification permission: requested lazily on the first fire attempt.

export interface AlertFireContext {
  position: Position
  pool: Pool | undefined
  alert: PositionAlert
  message: string
}

/**
 * Evaluate one rule against a single position/pool snapshot. Returns
 * null if the rule doesn't trigger, otherwise a human-readable message.
 *
 * Exported so the test suite can pin the trigger logic without
 * needing a full React render.
 */
export function evaluateRule(
  alert: PositionAlert,
  position: Position,
  pool: Pool | undefined,
): string | null {
  if (!alert.active) return null
  if (!position.isActive) return null

  switch (alert.type) {
    case 'OUT_OF_RANGE': {
      if (!pool) return null
      const inRange = pool.activeBinId >= position.binRangeMin &&
                      pool.activeBinId <= position.binRangeMax
      if (!inRange) {
        return `Позиция ${position.tokenXSymbol}/${position.tokenYSymbol} вышла из диапазона ` +
               `(активный бин ${pool.activeBinId}, ваш диапазон ${position.binRangeMin}–${position.binRangeMax})`
      }
      return null
    }
    case 'FEES_THRESHOLD': {
      const totalFeesRub = position.unclaimedFeeX + position.unclaimedFeeY
      if (totalFeesRub >= alert.threshold) {
        return `Незабранные комиссии по ${position.tokenXSymbol}/${position.tokenYSymbol} достигли ${totalFeesRub.toLocaleString('ru-RU')} ` +
               `(порог ${alert.threshold.toLocaleString('ru-RU')})`
      }
      return null
    }
    case 'VALUE_DROP': {
      const initial = (position.initialDepositX ?? 0) + (position.initialDepositY ?? 0)
      const current = position.currentValueX + position.currentValueY
      if (initial <= 0) return null
      const dropPct = ((initial - current) / initial) * 100
      if (dropPct >= alert.threshold) {
        return `Стоимость позиции ${position.tokenXSymbol}/${position.tokenYSymbol} упала на ${dropPct.toFixed(1)}% ` +
               `(порог ${alert.threshold}%)`
      }
      return null
    }
  }
}

async function maybeRequestPermission(): Promise<NotificationPermission> {
  if (typeof Notification === 'undefined') return 'denied'
  if (Notification.permission === 'granted' || Notification.permission === 'denied') {
    return Notification.permission
  }
  // 'default' — ask once.
  try {
    return await Notification.requestPermission()
  } catch {
    return 'denied'
  }
}

function fire(ctx: AlertFireContext): void {
  void (async () => {
    const perm = await maybeRequestPermission()
    let delivered: 'browser' | 'in-app' = 'in-app'
    if (perm === 'granted') {
      try {
        new Notification('DLMM Alert', {
          body: ctx.message,
          tag: `dlmm-alert-${ctx.alert.id}`,
          icon: '/favicon.ico',
        })
        delivered = 'browser'
      } catch {
        // Fall through to AntD fallback below.
      }
    }
    if (delivered === 'in-app') {
      // Sprint 10 wave 3 — visible in-app fallback so a user with
      // notifications blocked still knows their rule fired. AntD
      // `notification` is the closest analogue to a browser toast.
      antdNotification.warning({
        message: 'Алерт по позиции',
        description: ctx.message,
        placement: 'topRight',
        duration: 8,
      })
    }
    positionAlertsStore.recordFired(ctx.alert.id)
    alertHistoryStore.record({
      alertId: ctx.alert.id,
      alertLabel: ctx.alert.label,
      alertType: ctx.alert.type,
      message: ctx.message,
      delivery: delivered,
      firedAt: new Date().toISOString(),
    })
  })()
}

/**
 * Sprint 10 wave 3 — public helper for the "Тест алерта" button in
 * the drawer. Manufactures a fake fire so the user can verify the
 * notification + permission flow without waiting for a real
 * out-of-range / threshold event.
 */
export function fireTestAlert(alert: PositionAlert): void {
  fire({
    alert,
    position: undefined as never, // not consumed by fire()
    pool: undefined,
    message: `[Тест] Это пример алерта «${alert.label}» — настоящее срабатывание будет выглядеть так же.`,
  })
}

/**
 * Hook: evaluates every alert against the supplied snapshot whenever
 * either side updates. Idempotent — runs once per (positions, pools)
 * pair; cooldown prevents re-fires.
 */
export function usePositionAlertWatcher(
  positions: Position[] | undefined,
  pools: Pool[] | undefined,
): void {
  useEffect(() => {
    if (!positions || !pools) return
    const poolById = new Map(pools.map((p) => [p.id, p]))
    const alerts = positionAlertsStore.list()

    for (const alert of alerts) {
      if (!alert.active || isOnCooldown(alert)) continue
      const position = positions.find((p) => p.id === alert.positionId)
      if (!position) continue
      const pool = poolById.get(position.poolId)
      const message = evaluateRule(alert, position, pool)
      if (message) fire({ alert, position, pool, message })
    }
  }, [positions, pools])
}
