import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Tag, Tooltip } from 'antd'
import { ExperimentOutlined } from '@ant-design/icons'

/**
 * QW-3 (Batch #4, Sprint 14) — personalized demo URL.
 *
 * <p>Sales rep generates a link like
 * {@code https://demo.sber-dlmm.com/?company=Alpha%20Treasury}
 * before sending it to a prospect. We pick up the {@code company}
 * query param on first visit, persist it to
 * {@code localStorage.dlmm.demo.company}, and render a "Демо для:
 * Alpha Treasury" tag in the header for the rest of the session.
 *
 * <p>Why persist: the param is set by the rep ONCE; the prospect
 * then navigates 8-12 pages during the demo, each one rewriting the
 * URL via React Router's path push. Without persistence the
 * personalisation evaporates after the first click. localStorage
 * survives reload, which matters when the prospect comes back next day.
 *
 * <p>Cleanup: a tiny "×" in the tooltip-popover lets the user clear
 * the persistence — important so the same browser used for a real
 * pilot doesn't keep showing "Демо для:" forever.
 *
 * <p>Render: nothing if no company param has ever been seen on this
 * device. So real users never see this component.
 */
const STORAGE_KEY = 'dlmm.demo.company'

function readPersisted(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function persist(value: string | null): void {
  try {
    if (value) localStorage.setItem(STORAGE_KEY, value)
    else localStorage.removeItem(STORAGE_KEY)
  } catch {
    // ignore quota / private mode
  }
}

export default function DemoBranding() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [companyName, setCompanyName] = useState<string | null>(readPersisted)

  // One-shot URL → state + persistence handoff. Runs whenever the
  // URL param is present (not just on mount) so a rep can re-set the
  // brand mid-session by adding ?company=NewBrand to any URL.
  useEffect(() => {
    const fromUrl = searchParams.get('company')
    if (fromUrl && fromUrl.trim().length > 0 && fromUrl.length <= 60) {
      const trimmed = fromUrl.trim()
      persist(trimmed)
      setCompanyName(trimmed)
      // Strip the param so it doesn't pollute every subsequent share-URL.
      // setSearchParams with the existing params minus `company`.
      const next = new URLSearchParams(searchParams)
      next.delete('company')
      setSearchParams(next, { replace: true })
    }
  }, [searchParams, setSearchParams])

  if (!companyName) return null

  return (
    <Tooltip
      title={
        <span>
          Персонализированная демо-ссылка для {companyName}.
          <a
            href="#clear"
            style={{ marginInlineStart: 8, color: 'var(--sber-green-light, #4caf50)' }}
            onClick={(e) => {
              e.preventDefault()
              persist(null)
              setCompanyName(null)
            }}
          >
            Очистить
          </a>
        </span>
      }
    >
      <Tag
        icon={<ExperimentOutlined />}
        color="green"
        style={{
          borderRadius: 'var(--radius-pill)',
          padding: '0 12px',
          fontSize: 'var(--text-xs)',
          fontWeight: 600,
          cursor: 'help',
        }}
      >
        Демо для: {companyName}
      </Tag>
    </Tooltip>
  )
}
