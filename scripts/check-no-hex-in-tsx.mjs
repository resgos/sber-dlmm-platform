#!/usr/bin/env node
/**
 * AU-2 ratchet: block new hex colors from leaking into .tsx files.
 *
 * Audit `SYSTEM-AUDIT-2026-06-17.md` measured 209 hex occurrences across
 * 268+228 inline-style usages — too many to fix in one sprint. Strict
 * "no hex in tsx" would block the next PR; this ratchet allows the
 * existing 209 baseline but **fails CI if any file's count goes up**.
 *
 * The intent: design-token drift becomes a strictly-decreasing number.
 * Sprint 8 UX-DS-1 sweep drops top-10 files; each drop is committed
 * back to `scripts/hex-baseline.json` so the ceiling tightens.
 *
 * Usage:
 *   node scripts/check-no-hex-in-tsx.mjs            # check (CI mode, exit 1 on regression)
 *   node scripts/check-no-hex-in-tsx.mjs --update   # regenerate baseline (after a sweep)
 *   node scripts/check-no-hex-in-tsx.mjs --report   # show per-file counts, no exit code
 *
 * Allowlist: files in `ALLOWLIST` may contain hex without counting (these
 * are the source-of-truth files where palette literals legitimately live).
 *
 * Excluded by convention: `node_modules`, `dist`, `e2e/`, `*.test.tsx`.
 */

import { readdirSync, readFileSync, statSync, writeFileSync, existsSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = join(fileURLToPath(import.meta.url), '..', '..')
const BASELINE_PATH = join(REPO_ROOT, 'scripts', 'hex-baseline.json')

const SCAN_ROOTS = [
  'dlmm-user-ui/src',
  'dlmm-admin-ui/src',
]

const ALLOWLIST = new Set([
  // Source-of-truth files: palette literals legitimately live here.
  'dlmm-user-ui/src/components/TokenChip.tsx',
  'dlmm-user-ui/src/main.tsx',
  'dlmm-admin-ui/src/main.tsx',
])

const SKIP_PATTERNS = [
  /node_modules/,
  /[\\\/]dist[\\\/]/,
  /[\\\/]e2e[\\\/]/,
  /\.test\.tsx$/,
  /\.spec\.tsx$/,
]

// Hex color literal: #RGB / #RRGGBB / #RRGGBBAA, requires a # boundary so
// we don't catch URL fragments like `#section`. The lookahead enforces
// hex-only continuation. Lookbehind enforces non-alphanum prefix.
const HEX_RE = /(?<![\w&])#[0-9a-fA-F]{3,8}\b/g

const ARG = process.argv[2] ?? ''
const MODE = ARG === '--update' ? 'update' : ARG === '--report' ? 'report' : 'check'

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

function countHex(absPath) {
  const src = readFileSync(absPath, 'utf8')
  const matches = src.match(HEX_RE)
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
      const n = countHex(abs)
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
    console.error(`[hex-check] baseline at ${BASELINE_PATH} is invalid JSON: ${e.message}`)
    process.exit(2)
  }
}

function totalOf(counts) {
  return Object.values(counts).reduce((a, b) => a + b, 0)
}

function printReport(counts) {
  const total = totalOf(counts)
  console.log(`[hex-check] ${Object.keys(counts).length} .tsx files contain hex; total ${total} occurrences.`)
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
  console.log(`[hex-check] Baseline written to ${normaliseKey(BASELINE_PATH)}: ${Object.keys(sorted).length} files, ${totalOf(sorted)} total hex occurrences.`)
  process.exit(0)
}

// MODE === 'check'
const baseline = loadBaseline()
if (!baseline) {
  console.error(`[hex-check] No baseline at ${normaliseKey(BASELINE_PATH)}. Run with --update to create it.`)
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
  console.log(`[hex-check] ${improvements.length} files improved since baseline:`)
  for (const i of improvements.slice(0, 10)) {
    console.log(`  ${i.file}: ${i.was} → ${i.now}`)
  }
  console.log(`  Run \`node scripts/check-no-hex-in-tsx.mjs --update\` to tighten the baseline.`)
}

if (regressions.length === 0 && newFiles.length === 0) {
  console.log(`[hex-check] OK — no new hex colors in .tsx. Baseline: ${totalOf(baseline)} occurrences across ${Object.keys(baseline).length} files.`)
  process.exit(0)
}

if (regressions.length > 0) {
  console.error(`\n[hex-check] FAIL — ${regressions.length} file(s) added new hex colors:`)
  for (const r of regressions) {
    console.error(`  ${r.file}: was ${r.was}, now ${r.now} (+${r.now - r.was})`)
  }
}
if (newFiles.length > 0) {
  console.error(`\n[hex-check] FAIL — ${newFiles.length} new .tsx file(s) contain hex colors:`)
  for (const n of newFiles) {
    console.error(`  ${n.file}: ${n.count} hex occurrences`)
  }
}

console.error(`\nUse CSS variables / classes from sber-theme.css instead of inline hex.`)
console.error(`If a hex literal is legitimate (palette source-of-truth), add the file to ALLOWLIST in scripts/check-no-hex-in-tsx.mjs.`)
console.error(`Otherwise, replace with var(--sber-*) and re-run.`)
process.exit(1)
