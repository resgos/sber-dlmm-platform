# Sber DLMM — Risk Register

Top 18 risks ranked by **inherent severity × likelihood** before mitigation.
"Now" columns reflect post-Sprint-1 state (2026-05-16). Update on every
sprint review.

> Legend
> - **Severity**: 1 (cosmetic) → 5 (regulatory / data-loss / outage)
> - **Likelihood**: 1 (unlikely in a year) → 5 (will happen this quarter)
> - **Score** = S × L. Anything ≥ 12 is a blocker for go-live.

---

## Live before demo

| # | Risk | S | L | Score | Status | Owner | Action |
|---|------|---|---|-------|--------|-------|--------|
| 1 | **JWT secret leak** — default `change-me-in-production-...` could ship if `.env` isn't overridden | 5 | 2 | 10 | Mitigated: `${JWT_SECRET:?required}` fail-fast + `.env.example` template. Real secret never committed. | IT-lead | Vault integration tracked for Sprint 3. |
| 2 | **Lost balance mutation on crash** — token-service updates balance then crashes before Kafka send → outbox/event lost, ledger drift | 5 | 3 | 15 | **CLOSED (Sprint 1 + Sprint 2).** Transactional outbox in both token-service and pool-engine. Cross-service kill-Kafka drill verified zero loss. Commits `d03742a`, `eed99a3`. | Backend lead | Done. |
| 3 | **Postgres single-node** — no replica, no PITR, container restart loses uncommitted ledger ops | 5 | 2 | 10 | OPEN. WAL streaming + nightly base backup deferred to Sprint 3. Demo: docker volume only. | Ops | RPO/RTO contract owed by PO. |
| 4 | **Liquibase changeset model is "MARK_RAN"-hack** — future schema changes won't apply on existing DBs | 4 | 4 | 16 | OPEN. Tactical workaround in place. Proper split (remove CREATE TABLE from init-db.sql, move to Liquibase changesets) tracked. | Backend lead | Sprint 3. |
| 5 | **Service-to-service auth bypass** — pool-engine accepts any valid JWT, no scope check on internal endpoints | 4 | 3 | 12 | Partially mitigated: shared filter rejects refresh tokens. No internal-only audience claim yet. | Backend lead | Add `aud=internal` claim + check, Sprint 3. |
| 6 | **Rate limit bypass** — only applied at gateway; direct service ports (8081–8088) are exposed in dev compose | 3 | 4 | 12 | OPEN. Per-service listener bind 127.0.0.1 in compose; nginx in front for staging. | Ops | Sprint 3. |
| 7 | **No CI/CD** — every change is built locally and pushed straight to demo | 3 | 5 | 15 | **CLOSED (Sprint 2).** GitHub Actions for backend (Maven + JDK 21), frontend (Vitest, both UIs), container-scan (Trivy weekly). Commit `3429ab2`. Required-status-check gating still admin action. | DevOps | Promote to required check. |
| 8 | **Front-end XSS via token list** — token name/symbol rendered without sanitisation | 4 | 2 | 8 | OPEN. AntD `Typography` does not auto-sanitise; CSP not set. | UI lead | Add CSP + DOMPurify on user-supplied fields. Sprint 3. |
| 9 | **CORS allows everything** — dev gateway had `Access-Control-Allow-Origin: *` | 3 | 4 | 12 | **CLOSED (Sprint 2).** `dlmm.cors.allowed-origins` env-driven. `application.yml` = dev localhost; `application-prod.yml` = `sber-online.ru` only. Verified: localhost:3000 → 200, evil.com → 403. Commit `6003805`. | UI lead | Done. |

## Operational

| # | Risk | S | L | Score | Status | Owner | Action |
|---|------|---|---|-------|--------|-------|--------|
| 10 | **Pool-engine TVL aggregates mojibake-encoded** — Cyrillic comments double-UTF-8 in JSON | 2 | 5 | 10 | OPEN. UI displays correctly because browsers auto-detect, but API responses look wrong in raw form. | Backend lead | Force UTF-8 on Spring + DB at connection level. |
| 11 | **price-oracle is a stub** — values are seeded once, no real feed | 4 | 3 | 12 | OPEN. /actuator/health for price-oracle excluded from admin-bff fan-out for the same reason. | Backend lead | Real MOEX/Binance feed wiring, Sprint 4. |
| 12 | **Kafka consumer lag invisible** — no per-topic lag metric in /actuator/health | 3 | 3 | 9 | **CLOSED (Sprint 2).** `KafkaConsumerLagHealthIndicator` in notification-service. AdminClient computes lag per partition, DOWN > 1000. Drill verified. Commit `2b8a92a`. | Ops | Done. |
| 13 | **No DB connection-pool exhaustion alarm** — Hikari saturates silently under load | 3 | 4 | 12 | **Partially closed (Sprint 2).** `hikaricp_*` metrics scraped by Prometheus, dashboard panel exists in DLMM Overview. Alert rules (e.g. pending > 0 for 1m) still owed in `docker/prometheus/rules/`. | Ops | Sprint 3 — alert rules. |
| 14 | **/admin/dashboard cold-start ~2s** — first call after restart pays Spring lazy init + WebClient warmup | 2 | 5 | 10 | **CLOSED (Sprint 2).** `StartupWarmer` beans fire on `ApplicationReadyEvent` in pool-engine and admin-bff (~800ms each). First user request now warm. Commit `6003805`. | IT-lead | Done. |

## Demo / presentation

| # | Risk | S | L | Score | Status | Owner | Action |
|---|------|---|---|-------|--------|-------|--------|
| 15 | **Live demo crashes on stage** — running on laptop, no failover | 4 | 3 | 12 | Mitigated: kill-and-restore drill rehearsed (Sprint 1 acceptance). Backup screenshots + recorded video in `docs/demo/`. | PO | Practice 3× before demo. |
| 16 | **Audience asks about regulatory framework** — no answer prepared for CBR / 161-FZ compliance | 4 | 4 | 16 | OPEN. Compliance Q&A bank to be drafted with legal. | PO | Sprint 1 #6 demo-script Q&A bank covers tech; legal owed by PO before demo. |
| 17 | **Token-service goes DOWN mid-demo** — swap fails, balance drift if event lost | 5 | 2 | 10 | Mitigated this sprint: Resilience4j circuit-breaker + fail-fast 503 + healthcheck cascade visible on admin dashboard. Drift risk still open until outbox lands. | Backend lead | See risk #2. |
| 18 | **Performance question on numbers** — "what's your RPS budget?" without baseline | 3 | 4 | 12 | **CLOSED (Sprint 2).** k6 baseline in `loadtest/baseline.js` + numbers in `loadtest/README.md`: 34.5 rps sustained, /pools p99 23s @ 300 VUs, swap p99 42s with 96% errors (test-design pile-up on single balance). Diagnosis + Sprint 3 plan documented. Grafana DLMM Overview dashboard live for ongoing tracking. Commits `c230ef2`, `618bf9f`. | IT-lead | Sprint 3 — re-baseline after Hikari bump + multi-user mix. |

---

## Score summary (post-Sprint 2)

| Score band | Sprint 1 → Sprint 2 |
|---|---|
| Critical (≥ 15) | 4 → 1 |
| High (12–14) | 7 → 5 (counting #13 as partial) |
| Medium (8–11) | 5 → 4 |
| Low (< 8) | 2 → 2 |

**Closed in Sprint 2** (6 risks): #2 outbox, #7 CI/CD, #9 CORS,
#12 Kafka lag, #14 cold-start, #18 RPS baseline. Partial: #13 (Hikari
metrics scraped, alert rules pending).

**Still critical (#4 Liquibase split, #16 regulatory)** carry to Sprint 3.

*Last refreshed: Sprint 2 close (2026-05-16). Owners self-update; PO reviews at sprint close.*
