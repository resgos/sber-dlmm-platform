#!/usr/bin/env node
/**
 * Sprint 10 F-22 — Operator runbook generator.
 *
 * Walks docker/prometheus/rules/*.yml and emits a one-page Markdown
 * runbook per alert into docs/runbooks/. Each runbook carries:
 *   1. Alert summary + severity + team (from the rule)
 *   2. PromQL expression (verbatim — operators copy-paste into the
 *      Prometheus UI to inspect the live query)
 *   3. The annotation description (reason it fires)
 *   4. A curated KNOWN_CAUSES table for the alert (hand-maintained
 *      below — the generator merges per-alert hand notes with the
 *      auto-extracted skeleton)
 *   5. Quick-action checklist (auto + hand)
 *
 * Operators get a stable per-alert URL like
 *   docs/runbooks/HikariPoolSaturated.md
 * which Alertmanager / PagerDuty payloads link to via the
 * `runbook_url` annotation (Sprint 11 wire-up).
 *
 * Usage:
 *   node scripts/gen-alert-runbooks.mjs           # writes files
 *   node scripts/gen-alert-runbooks.mjs --check   # CI mode: exit 1
 *                                                   if any file is
 *                                                   stale vs source
 */

import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync, rmSync } from 'node:fs'
import { resolve, dirname, basename } from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const rulesDir = resolve(repoRoot, 'docker/prometheus/rules')
const outDir = resolve(repoRoot, 'docs/runbooks')
const isCheck = process.argv.includes('--check')

// --- per-alert hand-curated knowledge ---------------------------------
// Keyed by alert name. Each entry contributes a "Known causes" + "Quick
// actions" section. Without an entry the generator emits a TODO stub
// so an operator can fill it in on first incident — and the stub stays
// visible in code review.
const KNOWLEDGE = {
  HikariPoolSaturated: {
    causes: [
      'Long-running queries holding connections (look at `pg_stat_activity` WHERE state != "idle")',
      'Connection leak — service code not closing transactions (search logs for `Hikari leak`)',
      'Sudden traffic spike past `maximum-pool-size`',
      'Downstream Postgres slow — investigate Postgres CPU + IO first',
    ],
    actions: [
      'Open Grafana → DLMM Overview → "Hikari pool" panel and identify the offending service',
      'On that service: `kubectl logs --tail=200 <pod>` look for `Hikari` warnings',
      'If runaway query: `SELECT pg_cancel_backend(pid)` on the long-running query',
      'If sustained load: bump `dlmm.hikari.maximum-pool-size` (default 50) and restart pod',
      'Document the incident in `docs/runbooks/HikariPoolSaturated-incidents.md`',
    ],
  },
  HikariPoolNearLimit: {
    causes: [
      'Warm load creeping toward saturation',
      'Pre-cursor to `HikariPoolSaturated` — investigate while not yet paging',
    ],
    actions: [
      'Check if traffic correlates with a scheduled job (custody fee, outbox dispatcher cleanup)',
      'If sustained: capacity-plan a pool-size bump for next deploy window',
    ],
  },
  OutboxBacklogGrowing: {
    causes: [
      'Kafka broker unreachable — outbox builds up while dispatcher retries',
      'OutboxDispatcher thread crashed — `jstack <pid>` to confirm thread state',
      'Slow producer round-trip — check Kafka p99 latency',
    ],
    actions: [
      'Verify Kafka health: `docker exec dlmm-kafka kafka-topics --list --bootstrap-server kafka:9092`',
      'Check OutboxDispatcher logs: `docker logs dlmm-pool-engine | grep OutboxDispatcher`',
      'If Kafka is up but dispatcher stuck: restart the affected service pod',
      'Backlog auto-drains once the path recovers — no manual replay needed',
    ],
  },
  KafkaConsumerLagHigh: {
    causes: [
      'Consumer thread blocked on slow downstream call',
      'Partition rebalance in progress (transient — should clear in <30s)',
      'Producer surge — check `kafka_producer_record_send_rate`',
    ],
    actions: [
      'Open Grafana → "Kafka consumer lag" panel, identify the offending consumer group',
      'Tail consumer service logs for slow-downstream errors',
      'If consistent: scale the consumer (add a pod to the consumer group)',
    ],
  },
  HighErrorRate: {
    causes: [
      'Downstream service is DOWN — check `DownstreamServiceDown` alert',
      'Database connection refused — check Hikari',
      'Recent deploy regression — `kubectl rollout history` and consider rollback',
    ],
    actions: [
      '**PAGE THE ON-CALL** — this is a critical-severity alert.',
      'Identify the failing endpoint: Grafana → "5xx by URI" panel',
      'Pull last 200 ERROR-level log lines: `kubectl logs --tail=200 <pod> | grep ERROR`',
      'If recent deploy: `kubectl rollout undo deployment/<svc>` and retest',
      'Open incident channel; document timeline as the investigation unfolds',
    ],
  },
  SlowSwapP99: {
    causes: [
      'Same-pool row-lock contention — R#20 in the risk register',
      'Hikari saturation (precursor) — check `HikariPoolSaturated`',
      'Pool-engine GC pressure — heap full, evidenced by `jvm_gc_pause_seconds`',
      'Kafka producer flush latency on swap outbox',
    ],
    actions: [
      'Open Grafana → "Swap p99 by pool" panel — is it one pool or all?',
      'If one pool: check `pool_engine_row_lock_wait_seconds` for that pool',
      'If all pools: check JVM heap + Hikari first',
      'If GC pressure: schedule a pod restart in a maintenance window',
    ],
  },
  DownstreamServiceDown: {
    causes: [
      'Pod crashed (`kubectl get pods` shows CrashLoopBackOff)',
      'Network partition between Prometheus and the service',
      'Service stuck on startup — long Liquibase migration, etc.',
    ],
    actions: [
      '**PAGE THE ON-CALL** — critical.',
      '`kubectl describe pod <svc>` and check Events for OOMKilled / ImagePullErr',
      '`kubectl logs <svc>` last 100 lines',
      'If recent deploy: roll back. If config issue: edit & re-deploy.',
      'Post-incident: open a follow-up to add a startup-probe tunable if missing',
    ],
  },
}

// --- YAML mini-parser -------------------------------------------------
// We need just the subset emitted by Prometheus rule files: groups[].rules[]
// with alert/expr/for/labels/annotations. A real YAML lib would be a
// dep; this is ~40 LOC and good enough for our format.
function parseRulesYaml(text) {
  const lines = text.split(/\r?\n/)
  const alerts = []
  let group = null
  let alert = null
  let inLabels = false
  let inAnnots = false
  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i]
    const line = raw.replace(/#.*$/, '').trimEnd()
    if (!line.trim()) continue
    const indent = raw.match(/^ */)[0].length

    if (indent === 2 && line.trim().startsWith('- name:')) {
      group = line.split('name:')[1].trim()
      continue
    }
    if (indent === 6 && line.trim().startsWith('- alert:')) {
      if (alert) alerts.push(alert)
      alert = { group, name: line.split('alert:')[1].trim(), expr: '', for: '', labels: {}, annotations: {} }
      inLabels = false
      inAnnots = false
      continue
    }
    if (!alert) continue
    const key = line.trim().split(':')[0]
    if (key === 'expr') {
      // Expr can span multiple lines via the `expr: foo\n  and on (..) bar\n` indented continuation.
      const tail = line.trim().substring(5).trim()
      alert.expr = tail
      // Slurp continuation lines (indented further than 'expr:').
      let j = i + 1
      while (j < lines.length && /^\s+/.test(lines[j]) && lines[j].match(/^ */)[0].length > indent && !lines[j].trim().match(/^(for|labels|annotations|expr|- alert):/)) {
        alert.expr += ' ' + lines[j].trim()
        j++
      }
      i = j - 1
      continue
    }
    if (key === 'for') {
      alert.for = line.split('for:')[1].trim()
      continue
    }
    if (key === 'labels') { inLabels = true; inAnnots = false; continue }
    if (key === 'annotations') { inAnnots = true; inLabels = false; continue }

    if (inLabels && line.includes(':')) {
      const [k, ...rest] = line.trim().split(':')
      alert.labels[k.trim()] = rest.join(':').trim().replace(/^"|"$/g, '')
    } else if (inAnnots && line.includes(':')) {
      const [k, ...rest] = line.trim().split(':')
      alert.annotations[k.trim()] = rest.join(':').trim().replace(/^"|"$/g, '')
    }
  }
  if (alert) alerts.push(alert)
  return alerts
}

// --- renderer ---------------------------------------------------------
function renderRunbook(alert) {
  const k = KNOWLEDGE[alert.name]
  const causes = k?.causes ?? [
    'TODO — fill in on first incident; see header for the generator.',
  ]
  const actions = k?.actions ?? [
    'TODO — first responder, document your steps here as you go.',
  ]

  return `# Runbook — \`${alert.name}\`

> Auto-generated by \`scripts/gen-alert-runbooks.mjs\` from
> \`docker/prometheus/rules/dlmm.yml\`. Edit the rule there or the
> KNOWLEDGE table in the script — never edit this file by hand
> (the \`--check\` mode is wired into CI and will fail on drift).

| Field | Value |
|---|---|
| Severity | \`${alert.labels.severity ?? '—'}\` |
| Team | \`${alert.labels.team ?? '—'}\` |
| Group | \`${alert.group ?? '—'}\` |
| For (debounce) | \`${alert.for ?? '—'}\` |

## What this means

${alert.annotations.summary ? `**${alert.annotations.summary}**\n\n` : ''}${alert.annotations.description ?? 'No description.'}

## Live query

\`\`\`promql
${alert.expr.trim()}
\`\`\`

Copy this into the Prometheus UI (\`http://prometheus.dlmm.sber-online.ru/graph\`)
to inspect the current value and recent history.

## Known causes

${causes.map((c) => `- ${c}`).join('\n')}

## Quick actions

${actions.map((a, i) => `${i + 1}. ${a}`).join('\n')}

## Related

- Grafana dashboard: \`DLMM Overview\` (id \`dlmm-overview\`)
- Source rule: \`docker/prometheus/rules/dlmm.yml\`
- Generator: \`scripts/gen-alert-runbooks.mjs\` (\`--check\` enforces no-drift)
${k ? '' : '\n> ⚠️ No curated knowledge yet — fill in `KNOWLEDGE.' + alert.name + '` in the generator after the first incident.\n'}`
}

// --- main -------------------------------------------------------------
function main() {
  const yamlFiles = readdirSync(rulesDir).filter((f) => f.endsWith('.yml') || f.endsWith('.yaml'))
  if (yamlFiles.length === 0) {
    console.error('[runbook-gen] no rule files found in', rulesDir)
    process.exit(1)
  }

  const allAlerts = []
  for (const f of yamlFiles) {
    const text = readFileSync(resolve(rulesDir, f), 'utf8')
    allAlerts.push(...parseRulesYaml(text))
  }
  console.log(`[runbook-gen] parsed ${allAlerts.length} alerts from ${yamlFiles.length} rule file(s)`)

  if (!existsSync(outDir)) mkdirSync(outDir, { recursive: true })

  // Build the new state in-memory first, then diff against on-disk.
  const desired = new Map()
  for (const a of allAlerts) {
    desired.set(`${a.name}.md`, renderRunbook(a))
  }
  // Also emit an INDEX.md that lists everything.
  desired.set('INDEX.md', renderIndex(allAlerts))

  const onDisk = new Set(
    readdirSync(outDir)
      .filter((f) => f.endsWith('.md'))
  )

  let drift = 0
  for (const [name, content] of desired) {
    const path = resolve(outDir, name)
    const existing = existsSync(path) ? readFileSync(path, 'utf8').replace(/\r\n/g, '\n') : null
    if (existing !== content) {
      drift++
      if (isCheck) {
        console.error(`[runbook-gen] DRIFT ${name}`)
      } else {
        writeFileSync(path, content)
        console.log(`[runbook-gen] wrote ${name}`)
      }
    }
  }

  // Orphans — files on disk no longer matched by a rule. In write mode
  // we delete them (preventing stale runbooks for removed alerts).
  // In --check mode we report them as drift.
  for (const name of onDisk) {
    if (!desired.has(name)) {
      drift++
      if (isCheck) {
        console.error(`[runbook-gen] ORPHAN ${name}`)
      } else {
        rmSync(resolve(outDir, name))
        console.log(`[runbook-gen] removed orphan ${name}`)
      }
    }
  }

  if (isCheck && drift > 0) {
    console.error(`[runbook-gen] FAILED — ${drift} drift(s). Run \`node scripts/gen-alert-runbooks.mjs\` to regenerate.`)
    process.exit(1)
  }
  console.log(`[runbook-gen] OK — ${desired.size} runbooks, ${drift} updated`)
}

function renderIndex(alerts) {
  const bySeverity = { critical: [], warning: [], info: [], other: [] }
  for (const a of alerts) {
    const sev = a.labels.severity ?? 'other'
    ;(bySeverity[sev] ?? bySeverity.other).push(a)
  }
  const block = (title, list) => list.length === 0 ? '' :
    `## ${title}\n\n${list.map((a) => `- [\`${a.name}\`](./${a.name}.md) — ${a.annotations.summary ?? a.annotations.description ?? ''}`).join('\n')}\n`

  return `# Alert runbooks — index

Auto-generated by \`scripts/gen-alert-runbooks.mjs\`.

${block('Critical (page on-call)', bySeverity.critical)}
${block('Warning', bySeverity.warning)}
${block('Info', bySeverity.info)}
${block('Other', bySeverity.other)}

---

*${alerts.length} alerts total. Regenerate with \`node scripts/gen-alert-runbooks.mjs\`; CI enforces no-drift via \`--check\`.*
`
}

main()
