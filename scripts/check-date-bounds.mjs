#!/usr/bin/env node
/**
 * 2026-07-06 — verbatim date-bounds guard (both UIs).
 *
 * The timezone class fixed by fcaa07f/a30b38d began as innocent-looking
 * template literals: `${date}T00:00:00` — a LOCAL wall-clock string compared
 * against the UTC ledger, silently shifting every «за сегодня» window by the
 * viewer's offset. CI runs in UTC where the shift is zero, so behavioural
 * tests can't catch a reintroduction — this guard catches it by code shape.
 *
 * Rule: outside lib/apiDates.ts (the one legitimate home of bound
 * construction) and test files (which assert bound strings), no source line
 * may contain a zoneless T00:00:00 / T23:59:59 bound. Explicit-UTC strings
 * (`…T00:00:00Z`) — e.g. mock fixtures — are fine.
 *
 * Hard guard, not a ratchet: the current legitimate count is zero.
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const UIS = ['dlmm-user-ui', 'dlmm-admin-ui']

// Zoneless midnight/end-of-day bound NOT followed by Z or an offset.
const BOUND = /T(?:00:00:00|23:59:59)(?:\.\d{1,3})?(?![Z+\-\d])/

const isAllowed = (p) =>
  p.endsWith(`lib${path.sep}apiDates.ts`) ||
  /\.test\.(ts|tsx)$/.test(p) ||
  p.includes(`${path.sep}test${path.sep}`)

const walk = (dir, out = []) => {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name === 'dist') continue
      walk(p, out)
    } else if (/\.(ts|tsx)$/.test(e.name)) out.push(p)
  }
  return out
}

const offenders = []
for (const ui of UIS) {
  const src = path.join(ROOT, ui, 'src')
  if (!fs.existsSync(src)) continue
  for (const f of walk(src)) {
    if (isAllowed(f)) continue
    const lines = fs.readFileSync(f, 'utf8').split('\n')
    lines.forEach((line, i) => {
      if (BOUND.test(line)) offenders.push(`${path.relative(ROOT, f)}:${i + 1}: ${line.trim().slice(0, 100)}`)
    })
  }
}

if (offenders.length > 0) {
  console.error(`[date-bounds] ${offenders.length} verbatim local day-bound(s) — this reintroduces the UTC-shift class:`)
  for (const o of offenders) console.error(`  ${o}`)
  console.error('Use utcStartOfLocalDay / utcEndOfLocalDay from lib/apiDates.ts instead.')
  process.exit(1)
}
console.log('[date-bounds] OK — no verbatim local day-bounds outside lib/apiDates.ts.')
