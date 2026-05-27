# Batch Ship Summary — 2026-05-26 → 27

Living index всех batch-ов в текущей сессии. Updated после каждого ship.

## Cumulative count

| Batch | Date | PRs | Units | Theme |
|-------|------|-----|-------|-------|
| #2 | 2026-05-26 | 10 | 10 | Week-1 residuals + Sprint 13/14 features |
| Hotfix | 2026-05-26 | 0 | 2 | Audit + Spasibo JPQL fixes |
| #3 | 2026-05-26 | 0 | 7 + 2 bonus | Sprint 15 + tech debt |
| #4 | 2026-05-26 | 0 | 4 of 5 | Quick wins (CSV, /pricing, ?company=, JaCoCo) |
| #5 | 2026-05-26 | 0 | 3 of 4 | PO/Admin productivity (B-04, B-06, QW-5) |
| UX sweep | 2026-05-26 | 0 | 8 findings, 6 fixed | Volume/APY, chart freeze, tooltips, wording |
| Demo bug-fix | 2026-05-27 | 0 | 2 of 3 | Claim 0/0 + pilots empty |
| #6 polish | 2026-05-27 | 0 | 6 | last_login_at flow, ActivityLog wire, KPI tooltips, sort, column |

**Cumulative shipped:** 50+ features/fixes/docs across 8 batches. All on `claude/elated-elgamal-dba521`.

---

## Roadmap items shipped

### Sprint 13 (Production deployment)
- ✅ S13-01 Resilience4j coverage (admin-bff + fee-service)
- ✅ G-35 DR failover drill runbook
- ⏸ G-25 Helm cluster — external blocked
- ⏸ TD-2 Vault prod — external blocked
- ⏸ TD-6 WAL-G S3 — external blocked
- ⏸ TD-1 Liquibase cleanup — risky cross-service refactor

### Sprint 14 (Trust + Commercial)
- ✅ S14-01 SAML SSO scaffolding
- ✅ S14-02 Cohort analytics dashboard
- ✅ S14-03 Reviews + Simple-mode
- ✅ S13-02 User audit log
- ✅ G-02 Quote-execute idempotency tests
- ✅ NEW-2 Per-route rate-limit observability
- ⏸ F-01 Telegram bot — user excluded

### Sprint 15 (UX + Onboarding)
- ✅ NEW-4 PoolApyCalibrationService 30d-median
- ✅ G-23 Pool comparator pro metrics BE
- ✅ G-13 Video shooting script (production brief)
- ⚠️ NEW-3 EN i18n (partial — 2 of 12 pages)
- ⏳ G-24 1C connectors (not started)

### Business Improvements (BUSINESS-IMPROVEMENTS-PLAN)
- ✅ B-04 Weekly CS metrics export endpoint
- ✅ B-06 Pilot health dashboard
- ✅ QW-1 CSV download buttons × 3 tables
- ✅ QW-2 /pricing static page
- ✅ QW-3 ?company= demo URL
- ✅ QW-5 Activity log component + wired в 3 detail pages

### Testing Investments
- ✅ TI-2 JaCoCo coverage wiring

### UX / Polish / Bugs
- ✅ R-01..R-05 (5 week-1 residuals)
- ✅ Hotfixes audit + Spasibo
- ✅ F-01..F-08 (6 UX findings fixed, 2 deferred)
- ✅ Demo bug-fixes (claim backfill, last_login)
- ✅ Pool detail load optim (React Query)
- ✅ ESLint inline-style ratchet wired
- ✅ Batch #6 polish (login update, ActivityLog wired, KPI tooltips, sort)

---

## Still pending (по приоритету)

### Highest-leverage (можно делать solo)
1. NEW-3 EN i18n остальные 10 страниц
2. G-24 1C connectors CSV/XML export

### External-blocked (ждут infra/contract)
- Sprint 13 Helm/Vault/WAL-G
- B-01 Tier upsell + Sber Платежи
- B-15 Sber Online deep link
- G-13 actual video production

### Sprint 16+ regulatory
- F-17 AML SAR
- F-18 Market surveillance
- G-30 НРД custody
- G-33 Pentest

---

## Demo readiness

| Component | Status |
|---|---|
| 9 Spring services healthy | ✅ |
| 2 UIs serving fresh bundles | ✅ |
| 6 infra healthy (PG/Redis/Kafka/CH/Prom/Grafana) | ✅ |
| Auth + JWT refresh | ✅ |
| Pools list (real volume + APY) | ✅ |
| Swap E2E (atomic balance changes) | ✅ |
| Add liquidity E2E (preview + execute + TVL change) | ✅ |
| Fee claim E2E (with backfilled accruals) | ✅ |
| Admin /cohorts (Batch #5) | ✅ |
| Admin /pilots health (Batch #5 + last_login_at) | ✅ |
| Admin Activity log sidebars (Batch #6) | ✅ |
| /pricing public landing | ✅ |
| SAML SSO scaffold | ✅ |

**Origin HEAD:** `claude/elated-elgamal-dba521` — multi-commit chain with all batches.

---

*Updated 2026-05-27 после Batch #6 ship.*
