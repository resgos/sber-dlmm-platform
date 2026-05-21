#!/usr/bin/env node
/**
 * Sprint 9-DS-r4 P2-17 — UI shared-code drift watchdog.
 *
 * The admin-ui and user-ui carry a handful of identical (or near-identical)
 * files: format helpers, KpiTile, TokenPairChip, etc. The proper fix is a
 * pnpm/yarn workspace package (`dlmm-ui-common`) and a Sprint-10-sized
 * L-effort refactor. Until that lands, this script keeps the duplicates
 * in lockstep so a fix landing in one UI doesn't silently rot in the
 * other.
 *
 * Usage:
 *   node scripts/check-ui-shared-drift.mjs          # CI-mode (exit 1 on drift)
 *   node scripts/check-ui-shared-drift.mjs --diff   # print unified diffs
 *
 * Wired into .github/workflows/frontend.yml right after hex-ratchet.
 */

import { readFileSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

/**
 * Manifest of files that must stay in sync between the two UIs.
 *
 * `strict: true`  — every byte must match (used for pure-logic modules).
 * `strict: false` — files may legitimately diverge by a documented set
 *                   of lines; the runner emits an INFO note but doesn't
 *                   fail. Useful for components where one UI extends
 *                   the prop type but the implementation core is shared.
 *
 * When you intentionally diverge a `strict: true` entry, either flip
 * the strict flag with a comment explaining why, or promote both copies
 * to identical (preferred — preserves the migration path to the
 * workspace package).
 */
const SHARED_FILES = [
  {
    path: 'lib/format.ts',
    strict: true,
    why: 'Pure formatter helpers — formatCompact / formatTokenAmount. Identical contract on both sides; the migration target is packages/dlmm-ui-common/src/format.ts.',
  },
  {
    path: 'utils/format.ts',
    strict: true,
    why: 'Legacy formatter location (bpsToPercent etc.). Same migration target as lib/format.ts; consolidated in the workspace refactor.',
  },
  {
    path: 'components/sber/TokenPairChip.tsx',
    strict: true,
    why: 'Pair-symbol pill (e.g. SRUB/SBER) — visual atom, no app-specific props. Identical between admin and user UIs.',
  },
  {
    path: 'components/sber/KpiTile.tsx',
    strict: false,
    why: 'user-ui widens `sub` prop to ReactNode (P2-4 — "Моя доля" multi-line sub). Admin still uses string-only. When admin needs the wider type, copy from user-ui rather than diverging further.',
  },
]

const ADMIN_ROOT = resolve(repoRoot, 'dlmm-admin-ui/src')
const USER_ROOT = resolve(repoRoot, 'dlmm-user-ui/src')

const wantDiff = process.argv.includes('--diff')
let failures = 0
let infos = 0

function read(path) {
  if (!existsSync(path)) return null
  // Normalise CRLF → LF so Windows checkouts don't false-trip the watchdog.
  return readFileSync(path, 'utf8').replace(/\r\n/g, '\n')
}

function shortDiff(a, b) {
  const aLines = a.split('\n')
  const bLines = b.split('\n')
  const lines = []
  const max = Math.max(aLines.length, bLines.length)
  for (let i = 0; i < max; i++) {
    if (aLines[i] !== bLines[i]) {
      lines.push(`  L${i + 1}: ADMIN: ${JSON.stringify(aLines[i] ?? '')}`)
      lines.push(`         USER:  ${JSON.stringify(bLines[i] ?? '')}`)
      if (lines.length > 12) {
        lines.push('  …(truncated)…')
        break
      }
    }
  }
  return lines.join('\n')
}

for (const entry of SHARED_FILES) {
  const adminPath = resolve(ADMIN_ROOT, entry.path)
  const userPath = resolve(USER_ROOT, entry.path)
  const adminContent = read(adminPath)
  const userContent = read(userPath)

  if (adminContent === null && userContent === null) {
    // Both sides have nothing — fine, manifest entry is stale.
    continue
  }
  if (adminContent === null || userContent === null) {
    console.error(`[ui-drift] MISSING  ${entry.path}`)
    console.error(`           admin: ${adminContent === null ? 'absent' : 'present'}`)
    console.error(`           user:  ${userContent === null ? 'absent' : 'present'}`)
    failures++
    continue
  }

  if (adminContent === userContent) {
    console.log(`[ui-drift] OK       ${entry.path}`)
    continue
  }

  if (entry.strict) {
    console.error(`[ui-drift] DRIFT    ${entry.path}  (strict)`)
    console.error(`           ${entry.why}`)
    if (wantDiff) console.error(shortDiff(adminContent, userContent))
    failures++
  } else {
    console.log(`[ui-drift] DIVERGE  ${entry.path}  (relaxed — documented)`)
    console.log(`           ${entry.why}`)
    infos++
  }
}

console.log()
console.log(`[ui-drift] checked ${SHARED_FILES.length} entries — ${failures} drift, ${infos} documented divergence`)

if (failures > 0) {
  console.error('[ui-drift] FAILED — fix the strict drifts above, or promote the entry to relaxed with a documented justification.')
  process.exit(1)
}
