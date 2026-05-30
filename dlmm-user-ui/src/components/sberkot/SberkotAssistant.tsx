import { useEffect, useMemo, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { Button, Typography } from 'antd'
import { CloseOutlined } from '@ant-design/icons'
import SberkotMascot from './SberkotMascot'
import { resolveHint, OFF_KEY, seenKey } from './hints'
import { SBERKOT_CELEBRATE, type SberkotCelebrateDetail } from './events'

const { Text } = Typography

/**
 * SK-01 — «Сберкот» floating assistant.
 *
 * Route-aware contextual hints. The mascot FAB is always available (click to
 * summon a hint); on entering a route we auto-open its hint once, unless the
 * user dismissed that route's hint ("Понятно") or turned hints off globally
 * ("Скрыть подсказки"). Hint resolution + persistence keys live in ./hints
 * (pure, unit-tested in src/test/sberkotHints.test.ts).
 *
 * SK-01 v2 — Сберкот also *reacts* to successful actions: a `celebrateSberkot()`
 * call (see ./events) flips him to the celebrate pose and pops a short
 * congratulatory bubble for a few seconds, even if hints are globally off
 * (it's success feedback, not a hint).
 *
 * Styling per docs/DESIGN-DIRECTION: Tier-3 overlay bubble (--bg-card,
 * --shadow-lg, --radius-lg) with a brand accent bar; the mascot stays brand
 * green in both themes. Wobble + reduced-motion handled in sber-theme.css.
 */
const ls = {
  get: (k: string) => { try { return localStorage.getItem(k) } catch { return null } },
  set: (k: string, v: string) => { try { localStorage.setItem(k, v) } catch { /* ignore */ } },
}

const CELEBRATE_MS = 6000

export default function SberkotAssistant() {
  const { pathname } = useLocation()
  const hint = useMemo(() => resolveHint(pathname), [pathname])
  const [open, setOpen] = useState(false)
  const [globallyOff, setGloballyOff] = useState(() => ls.get(OFF_KEY) === '1')
  // SK-01 v2 — transient celebration message (overrides the hint while shown).
  const [celebration, setCelebration] = useState<string | null>(null)
  const celebrateTimer = useRef<number | null>(null)

  // Auto-open this route's hint once (unless seen or globally off).
  useEffect(() => {
    if (globallyOff) { setOpen(false); return }
    const alreadySeen = ls.get(seenKey(hint.key)) === '1'
    setOpen(!alreadySeen)
  }, [hint.key, globallyOff])

  // SK-01 v2 — listen for celebration events fired from anywhere in the app.
  useEffect(() => {
    const onCelebrate = (e: Event) => {
      const msg = (e as CustomEvent<SberkotCelebrateDetail>).detail?.message || 'Готово!'
      setCelebration(msg)
      setOpen(true)
      if (celebrateTimer.current) window.clearTimeout(celebrateTimer.current)
      celebrateTimer.current = window.setTimeout(() => {
        setCelebration(null)
        setOpen(false)
      }, CELEBRATE_MS)
    }
    window.addEventListener(SBERKOT_CELEBRATE, onCelebrate)
    return () => {
      window.removeEventListener(SBERKOT_CELEBRATE, onCelebrate)
      if (celebrateTimer.current) window.clearTimeout(celebrateTimer.current)
    }
  }, [])

  // SK-01 v2 — end any in-flight celebration on navigation, so a stale success
  // bubble (or its pending auto-close timer) never bleeds onto — or closes —
  // the next route's hint. Firing a celebration is not a route change, so this
  // never cuts a fresh celebration short.
  useEffect(() => {
    if (celebrateTimer.current) {
      window.clearTimeout(celebrateTimer.current)
      celebrateTimer.current = null
    }
    setCelebration(null)
  }, [pathname])

  const dismissHere = () => {
    ls.set(seenKey(hint.key), '1')
    setOpen(false)
  }
  const turnOff = () => {
    ls.set(OFF_KEY, '1')
    setGloballyOff(true)
    setOpen(false)
  }
  const dismissCelebration = () => {
    if (celebrateTimer.current) window.clearTimeout(celebrateTimer.current)
    setCelebration(null)
    setOpen(false)
  }

  const showingCelebration = !!celebration
  const pose = showingCelebration ? 'celebrate' : hint.pose
  const title = showingCelebration ? 'Отлично! 🎉' : hint.title
  const text = showingCelebration ? celebration! : hint.text

  return (
    <div className={`sberkot-dock${showingCelebration ? ' sberkot-dock--celebrate' : ''}`} aria-live="polite">
      {open && (
        <div className="sberkot-bubble" role="status">
          <button
            className="sberkot-bubble__close"
            onClick={showingCelebration ? dismissCelebration : dismissHere}
            aria-label="Закрыть подсказку"
          >
            <CloseOutlined />
          </button>
          <Text strong style={{ display: 'block', fontSize: 'var(--text-md)', color: 'var(--text-primary)', marginBottom: 'var(--space-1)' }}>
            {title}
          </Text>
          <Text style={{ display: 'block', fontSize: 'var(--text-sm)', color: 'var(--text-paragraph)', lineHeight: 'var(--leading-snug)' }}>
            {text}
          </Text>
          {!showingCelebration && (
            <div style={{ display: 'flex', gap: 'var(--space-2)', marginTop: 'var(--space-3)', justifyContent: 'flex-end' }}>
              <Button size="small" type="text" onClick={turnOff} style={{ color: 'var(--text-muted)' }}>
                Скрыть подсказки
              </Button>
              <Button size="small" type="primary" onClick={dismissHere}>
                Понятно
              </Button>
            </div>
          )}
        </div>
      )}
      <button
        className="sberkot-fab"
        onClick={() => { if (showingCelebration) dismissCelebration(); else setOpen((v) => !v) }}
        aria-label={showingCelebration ? 'Закрыть сообщение Сберкота' : open ? 'Скрыть Сберкота' : 'Открыть подсказку Сберкота'}
        title="Сберкот — помощник"
      >
        <SberkotMascot pose={open || showingCelebration ? pose : 'idle'} size={56} animated={!open} />
      </button>
    </div>
  )
}
