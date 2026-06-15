#!/usr/bin/env node
/**
 * i18n ratchet: block new hardcoded Cyrillic UI strings from leaking into the
 * investor-facing user-ui .tsx files.
 *
 * Born from the NotificationBell localisation (2026-06): the whole header bell
 * rendered Russian regardless of language because its strings were literals, not
 * `t(...)` keys. An EN investor saw Cyrillic. Strict "no Cyrillic in tsx" would
 * block the next PR (there's a large existing backlog), so — like the hex
 * ratchet — this allows the current per-file baseline but **fails if a file's
 * count goes up**. Hardcoded-Russian drift becomes a strictly-decreasing number;
 * fix a component (route its strings through i18n) and re-baseline.
 *
 * Scope: ONLY dlmm-user-ui (the investor-facing SPA). dlmm-admin-ui is internal
 * RU operator tooling — not in scope for the EN goal.
 *
 * COMMENTS DON'T COUNT: this codebase has extensive Russian JSDoc / `//` notes,
 * which are fine. We strip block + line comments before scanning, so only
 * Cyrillic in string literals / JSX text / template literals is counted. The
 * strip is heuristic (won't perfectly handle `//` inside a string), but it only
 * ever UNDER-counts, which is safe for a ratchet (never a false regression).
 *
 * Usage:
 *   node scripts/check-no-cyrillic-in-tsx.mjs            # CI mode, exit 1 on regression
 *   node scripts/check-no-cyrillic-in-tsx.mjs --update   # regenerate baseline (after a sweep)
 *   node scripts/check-no-cyrillic-in-tsx.mjs --report   # per-file counts, no exit code
 *
 * Excluded by convention: node_modules, dist, e2e/, *.test.tsx, *.spec.tsx.
 */

import { readdirSync, readFileSync, statSync, writeFileSync, existsSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = join(fileURLToPath(import.meta.url), '..', '..')
const BASELINE_PATH = join(REPO_ROOT, 'scripts', 'cyrillic-baseline.json')

const SCAN_ROOTS = ['dlmm-user-ui/src']

// Files where Cyrillic string literals legitimately live and are NOT
// user-facing UI to translate (e.g. the i18n source bundles would be here if
// they were .tsx — they're .json, so not scanned). Start empty; add with a
// reason as real exceptions surface.
const ALLOWLIST = new Set([])

const SKIP_PATTERNS = [
  /node_modules/,
  /[\\/]dist[\\/]/,
  /[\\/]e2e[\\/]/,
  /\.test\.tsx$/,
  /\.spec\.tsx$/,
]

const CYRILLIC_RE = /[А-Яа-яЁё]+/g

const ARG = process.argv[2] ?? ''
const MODE = ARG === '--update' ? 'update' : ARG === '--report' ? 'report' : 'check'

/** Remove JS/TS comments so Russian JSDoc / // notes don't count as UI strings.
 *  Heuristic: strips /* ... *​/ blocks and // line comments (avoiding :// URLs).
 *  Only ever under-counts (safe for a ratchet). */
function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, '')      // block + JSDoc comments
    .replace(/(?<![:/])\/\/[^\n]*/g, '')   // // line/trailing comments (skip ://, ///)
}

/** Recursive walk yielding absolute paths of .tsx files. */
function* walkTsx(absDir) {
  let entries
  try { entries = readdirSync(absDir) } catch { return }
  for (const name of entries) {
    const abs = join(absDir, name)
    let st
    try { st = statSync(abs) } catch { continue }
    if (st.isDirectory()) {
      if (SKIP_PATTERNS.some((re) => re.test(abs))) continue
      yield* walkTsx(abs)
    } else if (name.endsWith('.tsx')) {
      if (SKIP_PATTERNS.some((re) => re.test(abs))) continue
      yield abs
    }
  }
}

function countCyrillic(absPath) {
  const src = stripComments(readFileSync(absPath, 'utf8'))
  const matches = src.match(CYRILLIC_RE)
  return matches ? matches.length : 0
}

function normaliseKey(absPath) {
  return relative(REPO_ROOT, absPath).split(sep).join('/')
}

function collectCounts() {
  const counts = {}
  for (const root of SCAN_ROOTS) {
    const absRoot = join(REPO_ROOT, root)
    for (const abs of walkTsx(absRoot)) {
      const key = normaliseKey(abs)
      if (ALLOWLIST.has(key)) continue
      const n = countCyrillic(abs)
      if (n > 0) counts[key] = n
    }
  }
  return counts
}

function loadBaseline() {
  if (!existsSync(BASELINE_PATH)) return null
  try {
    return JSON.parse(readFileSync(BASELINE_PATH, 'utf8'))
  } catch (e) {
    console.error(`[cyrillic-check] baseline at ${BASELINE_PATH} is invalid JSON: ${e.message}`)
    process.exit(2)
  }
}

const totalOf = (counts) => Object.values(counts).reduce((a, b) => a + b, 0)

function printReport(counts) {
  console.log(`[cyrillic-check] ${Object.keys(counts).length} user-ui .tsx files contain hardcoded Cyrillic; total ${totalOf(counts)} occurrences.`)
  const top = Object.entries(counts).sort((a, b) => b[1] - a[1]).slice(0, 15)
  for (const [f, n] of top) console.log(`  ${String(n).padStart(4)}  ${f}`)
  if (Object.keys(counts).length > 15) console.log(`  … ${Object.keys(counts).length - 15} more files`)
}

const current = collectCounts()

if (MODE === 'report') {
  printReport(current)
  process.exit(0)
}

if (MODE === 'update') {
  const sorted = Object.keys(current).sort().reduce((acc, k) => ({ ...acc, [k]: current[k] }), {})
  writeFileSync(BASELINE_PATH, JSON.stringify(sorted, null, 2) + '\n')
  console.log(`[cyrillic-check] Baseline written: ${Object.keys(sorted).length} files, ${totalOf(sorted)} total occurrences.`)
  process.exit(0)
}

// MODE === 'check'
const baseline = loadBaseline()
if (!baseline) {
  console.error(`[cyrillic-check] No baseline at ${normaliseKey(BASELINE_PATH)}. Run with --update to create it.`)
  process.exit(2)
}

const regressions = []
const newFiles = []
for (const [file, count] of Object.entries(current)) {
  const allowed = baseline[file] ?? 0
  if (file in baseline) {
    if (count > allowed) regressions.push({ file, was: allowed, now: count })
  } else {
    newFiles.push({ file, count })
  }
}

const improvements = []
for (const [file, was] of Object.entries(baseline)) {
  const now = current[file] ?? 0
  if (now < was) improvements.push({ file, was, now })
}

if (improvements.length > 0) {
  console.log(`[cyrillic-check] ${improvements.length} files improved since baseline (re-run --update to tighten):`)
  for (const i of improvements.slice(0, 10)) console.log(`  ${i.file}: ${i.was} → ${i.now}`)
}

if (regressions.length === 0 && newFiles.length === 0) {
  console.log(`[cyrillic-check] OK — no new hardcoded Cyrillic in user-ui .tsx. Baseline: ${totalOf(baseline)} occurrences across ${Object.keys(baseline).length} files.`)
  process.exit(0)
}

if (regressions.length > 0) {
  console.error(`[cyrillic-check] FAIL — hardcoded Cyrillic increased in ${regressions.length} file(s):`)
  for (const r of regressions) console.error(`  ${r.file}: ${r.was} → ${r.now} (route the new strings through i18n t(), or re-baseline if intentional)`)
}
if (newFiles.length > 0) {
  console.error(`[cyrillic-check] FAIL — ${newFiles.length} new file(s) with hardcoded Cyrillic (not in baseline):`)
  for (const n of newFiles) console.error(`  ${n.file}: ${n.count}`)
}
console.error(`[cyrillic-check] Use i18n t('…') for user-facing text. If this is intentional/non-UI, run: node scripts/check-no-cyrillic-in-tsx.mjs --update`)
process.exit(1)
