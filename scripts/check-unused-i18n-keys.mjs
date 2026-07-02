#!/usr/bin/env node
/**
 * 2026-06-17 — dead-i18n-keys ratchet (user-ui).
 *
 * Finds locale keys that exist in ru.json but are never referenced from
 * src/**\/*.{ts,tsx}. The motivating bug: `dashboard.hero.tooltips.portfolio`
 * existed in BOTH locales and *documented* the portfolio as wallet+LP, but was
 * never rendered — while the code computed wallet-only. Dead keys are
 * documentation that can silently lie.
 *
 * Detection:
 *  - used keys   = static literals in t('...') / t("...") / t(`...`) calls
 *                  (i18n.t(...) matches too);
 *  - dynamic use = template literals like t(`glossary.${term}.term`) — every
 *    prefix before the first ${ is auto-whitelisted, so any key under it
 *    counts as used (self-maintaining, no manual list to rot);
 *  - plurals     = key_one/_few/_many/_other/... count as used when their base
 *    key is used (i18next resolves the suffix at runtime).
 *
 * Ratchet semantics (same as check-no-hex-in-tsx): the current unused set is
 * pinned in scripts/unused-i18n-baseline.json; NEW dead keys fail the build,
 * removals are reported and tightened with --update.
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const UI = path.join(ROOT, 'dlmm-user-ui')
const LOCALE = path.join(UI, 'src', 'i18n', 'locales', 'ru.json')
const BASELINE = path.join(ROOT, 'scripts', 'unused-i18n-baseline.json')
const UPDATE = process.argv.includes('--update')

// Keys that are read through indirection the regexes can't see. Keep tiny;
// prefer fixing the call-site to a static literal or a template prefix.
const MANUAL_WHITELIST_PREFIXES = []

const flatten = (obj, prefix = '', out = []) => {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k
    if (v !== null && typeof v === 'object' && !Array.isArray(v)) flatten(v, key, out)
    else out.push(key)
  }
  return out
}

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

const allKeys = flatten(JSON.parse(fs.readFileSync(LOCALE, 'utf8')))
const files = walk(path.join(UI, 'src'))

const used = new Set()
const dynamicPrefixes = new Set(MANUAL_WHITELIST_PREFIXES)
// \bt\( — 't' must start its own word ('split(' / 'format(' don't match;
// 'i18n.t(' does, because '.' is a boundary).
const staticRe = /\bt\(\s*(['"`])([A-Za-z0-9_][\w.\-]*)\1/g
const dynamicRe = /\bt\(\s*`([^`$]*)\$\{/g

for (const f of files) {
  const src = fs.readFileSync(f, 'utf8')
  for (const m of src.matchAll(staticRe)) used.add(m[2])
  for (const m of src.matchAll(dynamicRe)) if (m[1]) dynamicPrefixes.add(m[1])
}

const PLURAL = /_(zero|one|two|few|many|other)$/
const isUsed = (key) => {
  if (used.has(key)) return true
  const base = key.replace(PLURAL, '')
  if (base !== key && used.has(base)) return true
  for (const p of dynamicPrefixes) if (key.startsWith(p)) return true
  return false
}

const unused = allKeys.filter((k) => !isUsed(k)).sort()

if (UPDATE) {
  fs.writeFileSync(BASELINE, JSON.stringify({ count: unused.length, keys: unused }, null, 2) + '\n')
  console.log(`[i18n-dead] baseline updated: ${unused.length} unused keys pinned.`)
  process.exit(0)
}

let baseline = { count: 0, keys: [] }
if (fs.existsSync(BASELINE)) baseline = JSON.parse(fs.readFileSync(BASELINE, 'utf8'))
const baselineSet = new Set(baseline.keys)

const fresh = unused.filter((k) => !baselineSet.has(k))
const fixed = baseline.keys.filter((k) => !unused.includes(k))

if (fresh.length > 0) {
  console.error(`[i18n-dead] ${fresh.length} NEW unused locale key(s) (exist in ru.json, never referenced):`)
  for (const k of fresh) console.error(`  - ${k}`)
  console.error('Wire the key up (t(\'…\')), delete it, or — for indirect reads — add a template-literal call the scanner can see.')
  process.exit(1)
}
if (fixed.length > 0) {
  console.log(`[i18n-dead] ${fixed.length} key(s) fixed since baseline:`)
  for (const k of fixed) console.log(`  ${k}`)
  console.log('Run `node scripts/check-unused-i18n-keys.mjs --update` to tighten the baseline.')
}
console.log(`[i18n-dead] OK — no new dead i18n keys. Baseline: ${baseline.count} known-unused keys.`)
