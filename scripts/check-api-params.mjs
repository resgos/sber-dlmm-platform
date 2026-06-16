#!/usr/bin/env node
/**
 * FE↔BE query-param contract check.
 *
 * Catches the silent-noop class hit 3× in this repo: the frontend sends a query
 * param under one name (txType / dateFrom / email) while the backend reads
 * another (type / from / query), so the filter is dropped and the endpoint
 * ignores it. This walks the FE api clients and the Spring controllers and
 * flags any FE call that sends a query param the matched backend endpoint does
 * NOT declare.
 *
 * Conservative by design (no CI false alarms): if a FE path doesn't match a
 * backend mapping it is SKIPPED (can't verify), and Spring's Pageable params
 * (page/size/sort, auto-bound without @RequestParam) are always allowed.
 *
 *   node scripts/check-api-params.mjs            # check (exit 1 on a mismatch)
 *   node scripts/check-api-params.mjs --report   # same, but always exit 0
 *   node scripts/check-api-params.mjs --list-be  # dump the parsed backend index
 */

import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = join(fileURLToPath(import.meta.url), '..', '..')
const PAGEABLE = new Set(['page', 'size', 'sort']) // Spring auto-binds these
const MODE = process.argv[2] ?? ''

const MAPPING = { Get: 'GET', Post: 'POST', Put: 'PUT', Patch: 'PATCH', Delete: 'DELETE' }

function* walk(dir, keep) {
  let entries
  try { entries = readdirSync(dir) } catch { return }
  for (const n of entries) {
    const abs = join(dir, n)
    let st
    try { st = statSync(abs) } catch { continue }
    if (st.isDirectory()) {
      if (!/[\\/](node_modules|target|dist|e2e)[\\/]/.test(abs + sep)) yield* walk(abs, keep)
    } else if (keep(abs)) {
      yield abs
    }
  }
}

const rel = (abs) => abs.replace(REPO_ROOT + sep, '').split(sep).join('/')

function normalize(p) {
  return (p.replace(/^\/api\/v1/, '')
    .replace(/\{[^}]+\}/g, '*')      // {id}
    .replace(/\$\{[^}]+\}/g, '*')    // ${id} template
    .replace(/\/+/g, '/')
    .replace(/\/$/, '')) || '/'
}

function requestParamName(attrs, type, varName) {
  if (attrs) {
    const m = attrs.match(/(?:value|name)\s*=\s*"([^"]+)"/) || attrs.match(/^\s*"([^"]+)"/)
    if (m) return m[1]
  }
  return varName
}

/** Build { "GET /normalized/path": Set<paramName> } from all Spring controllers. */
function backendIndex() {
  const idx = {}
  for (const file of walk(REPO_ROOT, (f) => f.endsWith('Controller.java') && /[\\/]src[\\/]main[\\/]/.test(f))) {
    const src = readFileSync(file, 'utf8')
    const clsAt = src.search(/\b(?:public\s+)?(?:final\s+)?class\s+\w+/)
    const head = clsAt > 0 ? src.slice(0, clsAt) : ''
    const baseM = head.match(/@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"/)
    const base = baseM ? baseM[1] : ''

    // Window each mapping up to the NEXT mapping (not the next '{' — @Operation /
    // @ApiResponses({…}) annotations sit between the mapping and the method
    // params and would truncate the signature). @RequestParam only appears in
    // method signatures, never in bodies, so the wider window is safe.
    const maps = [...src.matchAll(/@(Get|Post|Put|Patch|Delete)Mapping(?:\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"[^)]*\))?/g)]
    for (let i = 0; i < maps.length; i++) {
      const m = maps[i]
      const httpMethod = MAPPING[m[1]]
      const methodPath = m[2] || ''
      const end = i + 1 < maps.length ? maps[i + 1].index : src.length
      const window = src.slice(m.index, end)
      const params = new Set()
      const rpRe = /@RequestParam(\([^)]*\))?\s+(?:final\s+)?([\w<>,.\[\]?]+)\s+(\w+)/g
      let pm
      while ((pm = rpRe.exec(window))) {
        const attrs = pm[1] ? pm[1].slice(1, -1) : ''
        params.add(requestParamName(attrs, pm[2], pm[3]))
      }
      idx[`${httpMethod} ${normalize(base + '/' + methodPath)}`] = params
    }
  }
  return idx
}

/** Extract the query-param keys a chunk sends, across both FE styles:
 *  inline `params: { a, b }` AND dynamic `const params = { a }; params.b = …`. */
function chunkParamKeys(chunk) {
  const keys = new Set()
  const addLiteral = (body) => {
    for (const km of body.matchAll(/(?:^|[,{])\s*([A-Za-z_$][\w$]*)\s*(?::|,|$)/gm)) keys.add(km[1])
  }
  // inline `params: { … }` (greedy-ish but bounded to the object)
  const inline = chunk.match(/params\s*:\s*\{([\s\S]*?)\}/)
  if (inline) addLiteral(inline[1])
  // dynamic: `const/let params … = { … }` literal …
  const decl = chunk.match(/(?:const|let)\s+params\b[^=]*=\s*\{([\s\S]*?)\}/)
  if (decl) addLiteral(decl[1])
  // … plus `params.key = …` / `params['key'] = …` assignments
  for (const am of chunk.matchAll(/params\.([A-Za-z_$][\w$]*)\s*=/g)) keys.add(am[1])
  for (const am of chunk.matchAll(/params\[['"]([^'"]+)['"]\]\s*=/g)) keys.add(am[1])
  return keys
}

/** Find FE apiClient.<method>(path, …) calls. Each api method has one call;
 *  we chunk the file by method boundary so a call sees its own param sources
 *  (inline + dynamically-built) and never the next method's. */
function frontendCalls() {
  const calls = []
  for (const root of ['dlmm-user-ui/src/api', 'dlmm-admin-ui/src/api']) {
    for (const file of walk(join(REPO_ROOT, root), (f) => f.endsWith('.ts') && !f.endsWith('.test.ts'))) {
      const src = readFileSync(file, 'utf8')
      // Top-level method boundaries: exactly-2-space-indented `name: …` / `name(`
      // (the api objects are `export const X = { method: async (…) => {…}, … }`).
      // 2-or-more spaces would also match nested object keys and fragment methods.
      const bounds = [...src.matchAll(/^ {2}(\w+)\s*[:(]/gm)].map((b) => b.index)
      bounds.push(src.length)
      for (let i = 0; i < bounds.length - 1; i++) {
        const chunk = src.slice(bounds[i], bounds[i + 1])
        // `<[^(]*>` (not `<[^>]*>`) so nested generics like <PageResponse<User>> match.
        const callM = chunk.match(/apiClient\.(get|post|put|patch|delete)\s*(?:<[^(]*>)?\s*\(\s*(['"`])([^'"`]*)\2/)
        if (!callM) continue
        const params = chunkParamKeys(chunk)
        if (params.size === 0) continue
        calls.push({
          method: callM[1].toUpperCase(),
          path: callM[3],
          params,
          file: rel(file),
          line: src.slice(0, bounds[i] + chunk.indexOf(callM[0])).split('\n').length,
        })
      }
    }
  }
  return calls
}

const be = backendIndex()

if (MODE === '--list-be') {
  for (const k of Object.keys(be).sort()) console.log(`${k}  →  [${[...be[k]].join(', ')}]`)
  process.exit(0)
}

const findings = []
let matched = 0
for (const c of frontendCalls()) {
  if (c.params.size === 0) continue
  const beParams = be[`${c.method} ${normalize(c.path)}`]
  if (!beParams) continue // unmatched path → skip (conservative)
  matched++
  const bad = [...c.params].filter((p) => !beParams.has(p) && !PAGEABLE.has(p))
  if (bad.length) findings.push({ ...c, bad, beParams: [...beParams] })
}

if (findings.length === 0) {
  console.log(`[api-params] OK — ${matched} matched FE call(s); every query param is declared by its backend endpoint.`)
  process.exit(0)
}

console.error(`[api-params] ${findings.length} FE call(s) send query params the backend endpoint does NOT declare (likely a silent-noop filter):`)
for (const f of findings) {
  console.error(`  ${f.file}:${f.line}  ${f.method} ${f.path}`)
  console.error(`     unknown: [${f.bad.join(', ')}]   backend declares: [${f.beParams.join(', ')}]`)
}
console.error(`[api-params] Rename the FE param(s) to match the backend @RequestParam, or fix the controller.`)
process.exit(MODE === '--report' ? 0 : 1)
