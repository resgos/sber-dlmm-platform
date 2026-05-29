import { useEffect, useMemo, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { Button, Typography } from 'antd'
import { CloseOutlined } from '@ant-design/icons'
import SberkotMascot from './SberkotMascot'
import { resolveHint, OFF_KEY, seenKey } from './hints'

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
 * Styling per docs/DESIGN-DIRECTION: Tier-3 overlay bubble (--bg-card,
 * --shadow-lg, --radius-lg) with a brand accent bar; the mascot stays brand
 * green in both themes. Wobble + reduced-motion handled in sber-theme.css.
 */
const ls = {
  get: (k: string) => { try { return localStorage.getItem(k) } catch { return null } },
  set: (k: string, v: string) => { try { localStorage.setItem(k, v) } catch { /* ignore */ } },
}

export default function SberkotAssistant() {
  const { pathname } = useLocation()
  const hint = useMemo(() => resolveHint(pathname), [pathname])
  const [open, setOpen] = useState(false)
  const [globallyOff, setGloballyOff] = useState(() => ls.get(OFF_KEY) === '1')

  // Auto-open this route's hint once (unless seen or globally off).
  useEffect(() => {
    if (globallyOff) { setOpen(false); return }
    const alreadySeen = ls.get(seenKey(hint.key)) === '1'
    setOpen(!alreadySeen)
  }, [hint.key, globallyOff])

  const dismissHere = () => {
    ls.set(seenKey(hint.key), '1')
    setOpen(false)
  }
  const turnOff = () => {
    ls.set(OFF_KEY, '1')
    setGloballyOff(true)
    setOpen(false)
  }

  return (
    <div className="sberkot-dock" aria-live="polite">
      {open && (
        <div className="sberkot-bubble" role="status">
          <button className="sberkot-bubble__close" onClick={dismissHere} aria-label="Закрыть подсказку">
            <CloseOutlined />
          </button>
          <Text strong style={{ display: 'block', fontSize: 'var(--text-md)', color: 'var(--text-primary)', marginBottom: 'var(--space-1)' }}>
            {hint.title}
          </Text>
          <Text style={{ display: 'block', fontSize: 'var(--text-sm)', color: 'var(--text-paragraph)', lineHeight: 'var(--leading-snug)' }}>
            {hint.text}
          </Text>
          <div style={{ display: 'flex', gap: 'var(--space-2)', marginTop: 'var(--space-3)', justifyContent: 'flex-end' }}>
            <Button size="small" type="text" onClick={turnOff} style={{ color: 'var(--text-muted)' }}>
              Скрыть подсказки
            </Button>
            <Button size="small" type="primary" onClick={dismissHere}>
              Понятно
            </Button>
          </div>
        </div>
      )}
      <button
        className="sberkot-fab"
        onClick={() => setOpen((v) => !v)}
        aria-label={open ? 'Скрыть Сберкота' : 'Открыть подсказку Сберкота'}
        title="Сберкот — помощник"
      >
        <SberkotMascot pose={open ? hint.pose : 'idle'} size={56} animated={!open} />
      </button>
    </div>
  )
}
