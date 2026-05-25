#!/usr/bin/env node
/**
 * HOT-3 ratchet: block new inline pixel literals from leaking into .tsx files.
 *
 * Sprint 13 UI-CRITIQUE enforcement. Same shape as the hex ratchet
 * (`scripts/check-no-hex-in-tsx.mjs`) — snapshot-style baseline, fails only
 * on NEW violations, never on the existing backlog.
 *
 * Catches inline style props with hard-coded `fontSize: <number>` or
 * `borderRadius: <number>` literals. CSS variable strings such as
 * `fontSize: 'var(--text-xs)'` and `borderRadius: 'var(--radius-sm)'`
 * are by-design exempt.
 *
 * Usage:
 *   node scripts/check-inline-styles.mjs                  # check (CI mode, exit 1 on new violations)
 *   node scripts/check-inline-styles.mjs --write-baseline # regenerate baseline (after a cleanup)
 *   node scripts/check-inline-styles.mjs --report         # show top offenders, no exit code
 *
 * Baseline format: array of `{file, line, match, snippet}` sorted by
 * (file, line, match) so git diffs are stable across runs. `match` is the
 * identifying fragment (e.g. `fontSize: 14`); `snippet` is the surrounding
 * source line trimmed to 80 chars.
 *
 * Skipped:
 *   - node_modules, dist, e2e/, *.test.tsx, *.spec.tsx
 *   - files that import from `@/components/sber/` token helpers
 *     (by-design exception — those wrap design-token usage).
 */

import { readdirSync, readFileSync, statSync, writeFileSync, existsSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = join(fileURLToPath(import.meta.url), '..', '..')
const BASELINE_PATH = join(REPO_ROOT, 'scripts', 'inline-style-baseline.json')

const SCAN_ROOTS = [
  'dlmm-user-ui/src',
  'dlmm-admin-ui/src',
]

const SKIP_PATTERNS = [
  /node_modules/,
  /[\\\/]dist[\\\/]/,
  /[\\\/]e2e[\\\/]/,
  /\.test\.tsx$/,
  /\.spec\.tsx$/,
]

// Sber token-helper import — files that wrap design tokens are exempt.
const SBER_IMPORT_RE = /from\s+['"]@\/components\/sber\//

// Patterns we ratchet on. Each one looks for `<prop>: <number>` (no quotes,
// no `var(--…)`). The `style={{` window match is enforced by the outer
// scanner, not the regex — keeps the regex stable for false-positive
// tolerance.
const PIXEL_PROPS = ['fontSize', 'borderRadius']
const PIXEL_RE = new RegExp(`\\b(${PIXEL_PROPS.join('|')}):\\s*(\\d+)\\b`, 'g')

// Window size (in lines) to look ahead from `style={{` when finding the
// closing `}}`. 10 covers every multi-line style object in the codebase
// today (longest is ~9 lines).
const STYLE_WINDOW = 10

const ARG = process.argv[2] ?? ''
const MODE =
  ARG === '--write-baseline' || ARG === '--update'
    ? 'write-baseline'
    : ARG === '--report'
      ? 'report'
      : 'check'

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

function normaliseKey(absPath) {
  return relative(REPO_ROOT, absPath).split(sep).join('/')
}

/**
 * Scan a single file for inline pixel literals.
 *
 * Returns an array of `{file, line, match, snippet}` violations. `match` is
 * the identifying fragment for de-dup and baseline diffing; `snippet` is the
 * trimmed source line for human-readable error output.
 *
 * Strategy: find every `style={{` opener, then scan up to STYLE_WINDOW lines
 * forward until we hit the matching `}}` (naive — counts braces in the
 * window). Inside that window we run PIXEL_RE. False positives on tricky
 * cases (string concat, nested objects) are fine — they'll be stable in the
 * baseline and never cause a regression.
 */
function scanFile(absPath) {
  const src = readFileSync(absPath, 'utf8')
  if (SBER_IMPORT_RE.test(src)) return []
  const lines = src.split(/\r?\n/)
  const key = normaliseKey(absPath)
  const violations = []

  for (let i = 0; i < lines.length; i++) {
    const openerIdx = lines[i].indexOf('style={{')
    if (openerIdx === -1) continue

    // Build a window starting at the opener.
    const windowEnd = Math.min(lines.length, i + STYLE_WINDOW)
    // Track brace depth from the `{{` opener; stop when it returns to 0.
    let depth = 0
    let sawOpen = false
    const windowLines = []
    for (let j = i; j < windowEnd; j++) {
      const text = j === i ? lines[j].slice(openerIdx) : lines[j]
      windowLines.push({ line: j + 1, text })
      for (const ch of text) {
        if (ch === '{') { depth++; sawOpen = true }
        else if (ch === '}') depth--
      }
      if (sawOpen && depth <= 0) break
    }

    // Scan each line of the window for our patterns.
    for (const { line, text } of windowLines) {
      PIXEL_RE.lastIndex = 0
      let m
      while ((m = PIXEL_RE.exec(text)) !== null) {
        const snippet = text.trim().slice(0, 80)
        // `match` (e.g. `fontSize: 14`) is the identifying fragment — two
        // sibling matches on the same line produce two distinct violations.
        violations.push({ file: key, line, match: m[0], snippet })
      }
    }
  }

  // De-dup — overlapping `style={{` windows may revisit the same match.
  const seen = new Set()
  const deduped = []
  for (const v of violations) {
    const k = `${v.file}:${v.line}:${v.match}`
    if (seen.has(k)) continue
    seen.add(k)
    deduped.push(v)
  }
  return deduped
}

function collectViolations() {
  const all = []
  for (const root of SCAN_ROOTS) {
    const absRoot = join(REPO_ROOT, root)
    for (const abs of walkTsx(absRoot)) {
      all.push(...scanFile(abs))
    }
  }
  return sortViolations(all)
}

function sortViolations(arr) {
  return [...arr].sort((a, b) => {
    if (a.file !== b.file) return a.file < b.file ? -1 : 1
    if (a.line !== b.line) return a.line - b.line
    if (a.match !== b.match) return a.match < b.match ? -1 : 1
    return a.snippet < b.snippet ? -1 : a.snippet > b.snippet ? 1 : 0
  })
}

function loadBaseline() {
  if (!existsSync(BASELINE_PATH)) return null
  try {
    const parsed = JSON.parse(readFileSync(BASELINE_PATH, 'utf8'))
    if (!Array.isArray(parsed)) {
      console.error(`[inline-style-check] baseline must be a JSON array; got ${typeof parsed}`)
      process.exit(2)
    }
    return parsed
  } catch (e) {
    console.error(`[inline-style-check] baseline at ${BASELINE_PATH} is invalid JSON: ${e.message}`)
    process.exit(2)
  }
}

function keyOf(v) {
  // Match-based key — uniquely identifies a single literal even when two
  // pattern matches share the same source line.
  return `${v.file}:${v.line}:${v.match}`
}

function printReport(violations) {
  console.log(
    `[inline-style-check] ${violations.length} inline pixel-literal violation(s) across ${
      new Set(violations.map((v) => v.file)).size
    } file(s).`,
  )
  const byFile = {}
  for (const v of violations) byFile[v.file] = (byFile[v.file] ?? 0) + 1
  const top = Object.entries(byFile).sort((a, b) => b[1] - a[1]).slice(0, 15)
  for (const [f, n] of top) console.log(`  ${String(n).padStart(4)}  ${f}`)
  if (Object.keys(byFile).length > 15) {
    console.log(`  … ${Object.keys(byFile).length - 15} more files`)
  }
}

const current = collectViolations()

if (MODE === 'report') {
  printReport(current)
  process.exit(0)
}

if (MODE === 'write-baseline') {
  writeFileSync(BASELINE_PATH, JSON.stringify(current, null, 2) + '\n')
  console.log(
    `[inline-style-check] Baseline written to ${normaliseKey(BASELINE_PATH)}: ${
      current.length
    } violations across ${new Set(current.map((v) => v.file)).size} files.`,
  )
  process.exit(0)
}

// MODE === 'check'
const baseline = loadBaseline()
if (!baseline) {
  console.error(
    `[inline-style-check] No baseline at ${normaliseKey(
      BASELINE_PATH,
    )}. Run with --write-baseline to create it.`,
  )
  process.exit(2)
}

const baselineKeys = new Set(baseline.map(keyOf))
const currentKeys = new Set(current.map(keyOf))

const newViolations = current.filter((v) => !baselineKeys.has(keyOf(v)))
const fixedViolations = baseline.filter((v) => !currentKeys.has(keyOf(v)))

if (fixedViolations.length > 0) {
  console.log(`[inline-style-check] ${fixedViolations.length} violation(s) fixed since baseline:`)
  for (const v of fixedViolations.slice(0, 10)) {
    console.log(`  ${v.file}:${v.line}  ${v.match}`)
  }
  console.log(`  Run \`node scripts/check-inline-styles.mjs --write-baseline\` to tighten the baseline.`)
}

if (newViolations.length === 0) {
  console.log(
    `[inline-style-check] OK — no new inline pixel literals. Baseline: ${baseline.length} violation(s) across ${
      new Set(baseline.map((v) => v.file)).size
    } file(s).`,
  )
  process.exit(0)
}

console.error(`\n[inline-style-check] FAIL — ${newViolations.length} new inline pixel literal(s):`)
for (const v of newViolations) {
  console.error(`  ${v.file}:${v.line}: ${v.match}    in: ${v.snippet}`)
}
console.error(
  `\nUse CSS variables instead: \`fontSize: 'var(--text-xs)'\`, \`borderRadius: 'var(--radius-sm)'\`.`,
)
console.error(`See dlmm-user-ui/src/styles/sber-theme.css for the token catalog.`)
console.error(
  `If the literal is genuinely unavoidable, add the snippet to scripts/inline-style-baseline.json via \`node scripts/check-inline-styles.mjs --write-baseline\` and justify in PR review.`,
)
process.exit(1)
