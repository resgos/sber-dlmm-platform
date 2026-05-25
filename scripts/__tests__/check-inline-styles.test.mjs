#!/usr/bin/env node
/**
 * Unit tests for scripts/check-inline-styles.mjs.
 *
 * Run with:
 *   node --test scripts/__tests__/check-inline-styles.test.mjs
 *
 * These exercise the script as a subprocess against a temp fixture tree so we
 * keep the real baseline at scripts/inline-style-baseline.json untouched. The
 * script reads its scan roots and baseline path from constants relative to
 * REPO_ROOT, so we copy the script into the fixture and run it in-place.
 */

import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync, copyFileSync, existsSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const HERE = dirname(fileURLToPath(import.meta.url))
const SCRIPT_SRC = join(HERE, '..', 'check-inline-styles.mjs')

let sandbox
let scriptPath
let baselinePath

beforeEach(() => {
  sandbox = mkdtempSync(join(tmpdir(), 'inline-style-test-'))
  mkdirSync(join(sandbox, 'scripts'), { recursive: true })
  mkdirSync(join(sandbox, 'dlmm-user-ui', 'src'), { recursive: true })
  mkdirSync(join(sandbox, 'dlmm-admin-ui', 'src'), { recursive: true })
  scriptPath = join(sandbox, 'scripts', 'check-inline-styles.mjs')
  baselinePath = join(sandbox, 'scripts', 'inline-style-baseline.json')
  copyFileSync(SCRIPT_SRC, scriptPath)
})

afterEach(() => {
  rmSync(sandbox, { recursive: true, force: true })
})

/** Write a fixture .tsx file inside the sandbox user-ui. */
function writeFixture(relPath, content) {
  const abs = join(sandbox, relPath)
  mkdirSync(dirname(abs), { recursive: true })
  writeFileSync(abs, content)
}

/** Run the script in the sandbox and return {status, stdout, stderr}. */
function run(args = []) {
  const res = spawnSync(process.execPath, [scriptPath, ...args], {
    cwd: sandbox,
    encoding: 'utf8',
  })
  return { status: res.status, stdout: res.stdout, stderr: res.stderr }
}

test('--write-baseline creates a baseline JSON', () => {
  writeFixture(
    'dlmm-user-ui/src/A.tsx',
    `export const a = <div style={{fontSize: 14}}>hi</div>\n`,
  )
  const r = run(['--write-baseline'])
  assert.equal(r.status, 0, r.stderr)
  assert.ok(existsSync(baselinePath), 'baseline file should be created')
  const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'))
  assert.equal(baseline.length, 1)
  assert.equal(baseline[0].file, 'dlmm-user-ui/src/A.tsx')
  assert.equal(baseline[0].line, 1)
  assert.match(baseline[0].snippet, /fontSize: 14/)
})

test('check passes when current matches baseline', () => {
  writeFixture(
    'dlmm-user-ui/src/A.tsx',
    `export const a = <div style={{fontSize: 14}}>hi</div>\n`,
  )
  run(['--write-baseline'])
  const r = run([])
  assert.equal(r.status, 0, r.stderr)
  assert.match(r.stdout, /OK — no new inline pixel literals/)
})

test('check fails on new fontSize violation', () => {
  writeFixture('dlmm-user-ui/src/A.tsx', `export const a = <div>hi</div>\n`)
  run(['--write-baseline'])
  // Add a new violation
  writeFixture(
    'dlmm-user-ui/src/B.tsx',
    `export const b = <div style={{fontSize: 16}}>b</div>\n`,
  )
  const r = run([])
  assert.equal(r.status, 1)
  assert.match(r.stderr, /FAIL/)
  assert.match(r.stderr, /B\.tsx/)
})

test('check fails on new borderRadius violation', () => {
  writeFixture('dlmm-user-ui/src/A.tsx', `export const a = <div>hi</div>\n`)
  run(['--write-baseline'])
  writeFixture(
    'dlmm-user-ui/src/B.tsx',
    `export const b = <div style={{borderRadius: 8}}>b</div>\n`,
  )
  const r = run([])
  assert.equal(r.status, 1)
  assert.match(r.stderr, /borderRadius: 8/)
})

test('var(--*) strings are NOT flagged', () => {
  writeFixture(
    'dlmm-user-ui/src/A.tsx',
    `export const a = <div style={{fontSize: 'var(--text-xs)', borderRadius: 'var(--radius-sm)'}}>a</div>\n`,
  )
  const r = run(['--write-baseline'])
  assert.equal(r.status, 0)
  const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'))
  assert.equal(baseline.length, 0, 'var(--*) strings should not produce violations')
})

test('multi-line styles are detected', () => {
  writeFixture(
    'dlmm-user-ui/src/A.tsx',
    [
      `export const a = <div style={{`,
      `  padding: 16,`,
      `  fontSize: 18,`,
      `  color: 'red',`,
      `  borderRadius: 4,`,
      `}}>multi</div>`,
      ``,
    ].join('\n'),
  )
  run(['--write-baseline'])
  const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'))
  assert.equal(baseline.length, 2)
  const lines = baseline.map((v) => v.line).sort((a, b) => a - b)
  assert.deepEqual(lines, [3, 5])
})

test('files importing from @/components/sber/ are exempt', () => {
  writeFixture(
    'dlmm-user-ui/src/A.tsx',
    [
      `import { KpiTile } from '@/components/sber/KpiTile'`,
      `export const a = <div style={{fontSize: 99, borderRadius: 4}}>{KpiTile}</div>`,
      ``,
    ].join('\n'),
  )
  const r = run(['--write-baseline'])
  assert.equal(r.status, 0)
  const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'))
  assert.equal(baseline.length, 0, 'sber-importing files should be exempt')
})

test('.test.tsx and .spec.tsx files are skipped', () => {
  writeFixture(
    'dlmm-user-ui/src/A.test.tsx',
    `export const a = <div style={{fontSize: 99}}>x</div>\n`,
  )
  writeFixture(
    'dlmm-user-ui/src/B.spec.tsx',
    `export const b = <div style={{borderRadius: 99}}>x</div>\n`,
  )
  run(['--write-baseline'])
  const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'))
  assert.equal(baseline.length, 0, 'test/spec files should be skipped')
})

test('admin-ui scan root is included', () => {
  writeFixture(
    'dlmm-admin-ui/src/C.tsx',
    `export const c = <div style={{fontSize: 12}}>c</div>\n`,
  )
  run(['--write-baseline'])
  const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'))
  assert.equal(baseline.length, 1)
  assert.equal(baseline[0].file, 'dlmm-admin-ui/src/C.tsx')
})

test('baseline ordering is stable across runs', () => {
  writeFixture(
    'dlmm-user-ui/src/Z.tsx',
    `export const z = <div style={{fontSize: 14}}>z</div>\n`,
  )
  writeFixture(
    'dlmm-user-ui/src/A.tsx',
    `export const a = <div style={{borderRadius: 8}}>a</div>\n`,
  )
  run(['--write-baseline'])
  const first = readFileSync(baselinePath, 'utf8')
  run(['--write-baseline'])
  const second = readFileSync(baselinePath, 'utf8')
  assert.equal(first, second, 'baseline must be byte-stable across runs')
  // Alphabetical: A.tsx before Z.tsx
  const baseline = JSON.parse(first)
  assert.equal(baseline[0].file, 'dlmm-user-ui/src/A.tsx')
  assert.equal(baseline[1].file, 'dlmm-user-ui/src/Z.tsx')
})

test('removing a violation prints "fixed since baseline" hint', () => {
  writeFixture(
    'dlmm-user-ui/src/A.tsx',
    `export const a = <div style={{fontSize: 14}}>a</div>\n`,
  )
  run(['--write-baseline'])
  // Replace with a var-based version that shouldn't count.
  writeFixture(
    'dlmm-user-ui/src/A.tsx',
    `export const a = <div style={{fontSize: 'var(--text-xs)'}}>a</div>\n`,
  )
  const r = run([])
  assert.equal(r.status, 0)
  assert.match(r.stdout, /fixed since baseline/)
})

test('missing baseline produces a helpful error and exit 2', () => {
  writeFixture(
    'dlmm-user-ui/src/A.tsx',
    `export const a = <div style={{fontSize: 14}}>a</div>\n`,
  )
  const r = run([])
  assert.equal(r.status, 2)
  assert.match(r.stderr, /No baseline/)
})

test('--report mode exits 0 and shows summary', () => {
  writeFixture(
    'dlmm-user-ui/src/A.tsx',
    `export const a = <div style={{fontSize: 14, borderRadius: 8}}>a</div>\n`,
  )
  const r = run(['--report'])
  assert.equal(r.status, 0)
  assert.match(r.stdout, /2 inline pixel-literal violation/)
})
