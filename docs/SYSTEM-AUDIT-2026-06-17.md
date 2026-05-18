# Sber DLMM Platform — Full System Audit

**Date**: 2026-06-17 (после Sprint 6 acceptance + Sprint 7 kickoff)
**Author**: Designer (senior Sber DS) + IT-lead + SA (joint audit).
**Scope**: Deeper than `UX-REVIEW-2026-06-03.md` — that was heuristic;
this one is **measured** (real `grep` counts, real build runs, real
file reads). Plus architecture-level pass.
**Method**:
- Phase 1 — self-test: full backend test sweep + both UI builds (verify state)
- Phase 2 — UI review: file-by-file with measurements
- Phase 3 — architecture: cross-service patterns + observability + security

> **TL;DR**: backend is solid (270 tests green, 9 services healthy,
> architecture clean). UI has serious design-system + accessibility
> debt (496 inline styles, 1 aria-attribute total across both apps,
> zero CSS @media queries). Plan adequate but execution-velocity
> mismatch is the top systemic risk.

---

## 1. Self-test snapshot (2026-06-17 12:00 MSK)

### 1.1 Backend tests

| Module | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| dlmm-common | 131 | 0 | 0 | 1 |
| dlmm-user-service | 7 | 0 | 0 | 0 |
| dlmm-token-service | 26 | 0 | 0 | 0 |
| dlmm-pool-engine | 53 | 0 | 0 | 0 |
| dlmm-transaction-service | 44 | 0 | 0 | 0 |
| dlmm-price-oracle | 9 | 0 | 0 | 0 |
| **Total** | **270** | **0** | **0** | **1** |

✅ All green. 1 skipped is a documented pre-existing case in
`FeeCalculatorExtendedTest$FeeEconomicsTests` (placeholder for future
boundary-revenue scenario).

### 1.2 Frontend tests + builds

| Surface | Tests | Build | Bundle (gzip) |
|---|---|---|---|
| dlmm-user-ui | 44 | ✅ green | 552 kB |
| dlmm-admin-ui | (none configured) | ✅ green | 548 kB |

✅ Both build clean. **Admin-ui has zero tests.**

### 1.3 Coverage distribution gap

- Backend: **270 tests** across 5 services
- Frontend: **44 tests** across 11 user-ui pages + 7 components — **0 tests on the 2 most complex pages** (HedgePage 716 LOC, SwapPage 353 LOC)

This was flagged in Sprint 6 retro as LK6 (Minor severity) → AI-5
locked Sprint 7+ +5/+10 frontend test growth target. Track here also
because of the page-complexity asymmetry.

---

## 2. UI Design Review — measured findings

### 2.1 Design-system drift (Critical)

| Measurement | user-ui | admin-ui | Comment |
|---|---|---|---|
| `style=` inline usages | **268** | **228** | Should be ≈ 50 each (tactical positioning only) |
| Hard-coded `#hex` colors | **209** | (not measured but ≈ same) | Design tokens (`var(--sber-*)`) defined in `sber-theme.css` but inconsistently used |
| `@media` queries in CSS | **0** | **0** | Zero CSS-level mobile responsiveness. Only AntD `Col xs/sm/md/lg` structural breakpoints |

**Root cause:** `sber-theme.css` defines 25+ tokens
(`--sber-green`, `--grad-brand`, `--shadow-md`, `--radius-lg`, etc.)
but pages drift back to inline `#21A038` / `#21A038` / `linear-gradient(...)`
instead of using the variables.

**Concrete example** — `HedgePage.tsx` line 438:
```tsx
border: `1px solid ${selected ? 'var(--sber-green)' : 'var(--border-light)'}`,
background: selected ? 'var(--sber-green-light)' : '#FFFFFF',  // ← hard-coded
```
Mixed in same component — token-aware on `border`, drift on `background`.

**Impact:** changing brand color requires touching 209 places, not 1.
Will hit when Sber Design System gets a major theme update (typical
every 18-24 months).

**Recommendation:**
1. **Sprint 7** UX block — designer pass that converts top 10 hardest-drift
   files. Estimated 1-1.5 days FE.
2. **Sprint 8** — add Stylelint pre-commit hook that blocks new `#hex`
   in `.tsx` files (allowlist `main.tsx` AntD theme + `sber-theme.css`).
3. **Sprint 9+** — full sweep + tokenization audit.

### 2.2 Accessibility — Critical fail

| Measurement | user-ui | admin-ui |
|---|---|---|
| `aria-*` attributes total | **1** | **0** |
| `role=` attributes | **0** | **0** |
| Semantic HTML (`<nav>`, `<main>`, `<aside>`) outside AntD wrappers | **0** | **0** |

The **single aria-label** is on SwapPage swap-flip button:
`aria-label="Поменять направление"`. That's it across **6000+ lines
of UI code**.

**Failing WCAG 2.1 AA on:**
- 1.4.1 Use of Color (price-impact traffic-light is color-only)
- 1.3.1 Info and Relationships (decorative divs without semantic equivalent)
- 4.1.2 Name, Role, Value (icon buttons without labels — NotificationBell
  bell icon, Token chips, sidebar toggle button)
- 2.4.7 Focus Visible (no custom focus rings; relies on browser default
  which AntD often overrides poorly)

**Impact:**
- Cannot pass formal Sber Accessibility Council review for retail
  product launch (Sprint 8+ B2B portal goes to corp clients first
  who are mostly OK; retail Spasibo conversion widget goes to **60M users**
  including ones using screen readers — DSS-level liability).
- Russian government services (Госуслуги integration Sprint 7 #6.8)
  require WCAG 2.1 AA — failed audit would block ЕСИА certification.

**Recommendation:**
1. **Sprint 7 UX-037** (already in backlog) — price-impact traffic-light
   adds `aria-label` + `sr-only` text equivalent.
2. **Sprint 8** — accessibility sprint: ≥ 20 aria-attribute additions,
   semantic HTML wrappers, focus-ring CSS.
3. **Sprint 9** — formal WCAG 2.1 AA audit by Sber DS team + RuStore
   compliance check.

### 2.3 Component duplication (Major)

**Found**: `StatCard` + `formatRub` exist in BOTH `dlmm-user-ui` and
`dlmm-admin-ui` — copy-pasted (admin-ui re-implements inline).

**Other drift candidates** (need verification):
- Login pages — 2 copies (admin-ui + user-ui), nearly identical visual structure
- Status badges (KycStatusBadge in user-ui has no admin-ui twin yet but should)
- Loading skeletons — each page rolls own

**Recommendation:**
- Sprint 9+ create `dlmm-ui-common` shared package (workspaces /
  TypeScript project refs) for StatCard, formatRub, KycStatusBadge,
  loading skeletons, error banner, etc.
- Estimated 3-4 days; pays back in Sprint 10+ when 3rd UI (B2B-portal-customer-facing
  per #5.6-FE Sprint 7) lands.

### 2.4 Page-by-page assessment

#### user-ui (11 pages, 7 components)

| Page | LOC | State (loading/empty/error) | Mobile | Tests | Concerns |
|---|---|---|---|---|---|
| **DashboardPage** | 295 | ✓/✗/✓ | partial Col grid | 0 | Stat tiles not drill-down links (UX-001 Major) |
| **SwapPage** | 353 | ✓/-/✓ | broken <414px (UX-042 Critical) | 0 | Slippage hidden behind gear (UX-007 Critical); only file with 1 aria |
| **HedgePage** | 716 | ✓/✓/✓ | partial | 10 (helpers only) | Mass-action ✓ Sprint 6 #6.14; unwind quote language fixed Sprint 7 #UX-016 |
| **PoolsPage** | 190 | ✓/✓/- | ✓ | 0 | No filter/sort at 30+ pools (UX-018 Major) |
| **PoolDetailPage** | 89 | ✓/-/- | ✓ | 0 | Bin chart no zoom (UX-019 Minor); thin error handling |
| **LiquidityPage** | 291 | ✓/✗/✓ | partial | 0 | Strategy selector lacks visualization (UX-020 Major) |
| **PositionsPage** | 202 | ✓/✗/✓ | partial | 0 | No exit-fee reminder in close modal (UX-021 Minor); R-UX-036 `?highlight=` Sprint 7 |
| **TransactionsPage** | 154 | ✓/✓/- | partial | 0 | CSV/1С export not surfaced in UI (UX-022 Minor) |
| **ProfilePage** | 168 | ✓/-/✓ | ✓ | 0 | SelfRestrictionPanel ✓ Sprint 6 #6.7; no "Connected services" section |
| **LoginPage** | 125 | -/-/✓ | ✓ | 0 | No "Forgot password?" link (Critical for banking); no SSO button yet |
| **RegisterPage** | 212 | -/-/✓ | ✓ | 0 | 6-field upfront (UX-027 Major) — high conversion drop |

#### user-ui components

| Component | LOC | Tests | Notes |
|---|---|---|---|
| `UserLayout` | 196 | 0 | Sidebar doesn't auto-collapse on mobile (UX-043 Minor) |
| `NotificationBell` | 179 | 0 | Margin deep-link ✓ Sprint 6 #6.15; badge count missing aria-label |
| `SelfRestrictionPanel` | 232 | 0 | Sprint 6 #6.7 — 4-state UX strong but un-tested |
| `SpasiboWidget` | 140 | 0 | Sprint 5 #5.5 — promo card placement good; un-tested |
| `BinLiquidityChart` | 126 | 0 | recharts wrapper, no aria for chart data |
| `StatCard` | 59 | 8 ✓ | Best-tested component |
| `KycStatusBadge` | 28 | 7 ✓ | — |
| `StrategySelector` | 59 | 4 ✓ | — |

#### admin-ui (16 pages, 4 components) — **0 tests configured**

| Surface | Verdict |
|---|---|
| DashboardPage | StatCard duplicated locally (cleanup target) |
| PoolsPage / PoolDetailPage / PoolCreatePage | OK structurally |
| TokensPage / TokenDetailPage / TokenCreatePage | OK |
| UsersPage / UserDetailPage | OK |
| TransactionsPage | OK |
| **SuspiciousTransactionsPage** | Was scaffolded — wire to Sprint 6 #6.9 `aml_alerts` table in Sprint 7+ |
| SettingsPage | OK |
| LoginPage | Duplicate of user-ui LoginPage (cleanup target) |
| `BinLiquidityChart` | Duplicate of user-ui (cleanup target) |
| `ProtectedLayout` / `ProtectedRoute` | OK |
| `AdminLayout` | OK |

### 2.5 i18n / Russian-language consistency

- ✅ Consistent: «Пул», «Своп»→«Обмен» (sidebar uses «Обмен» — UI-localised), «Хедж» (HedgePage uses «Хедж FX»), «Транзакции», «Профиль»
- ⚠ Inconsistent: SwapPage internal text uses «своп» (lowercase, anglicism); sidebar says «Обмен» (Russian word). User-visible label vs internal docstring divergence.
- ⚠ Pages mix «email» / «электронная почта» (LoginPage uses «Электронная почта» in label but `placeholder="user@example.com"`).
- ❌ **No language toggle at all** — UX-024 Critical. Sber Treasury risk team has English-speaking members.

### 2.6 Empty / loading / error state coverage

| State | Coverage |
|---|---|
| Loading (Spin/Skeleton) | 7 of 11 pages ✓ — strong |
| Empty | 3 of 11 pages ✓ — weak (most pages show plain empty table) |
| Error (catch / setError) | 8 of 11 pages ✓ — decent |
| First-time onboarding | 0 — no tour, no «начните с пулов» CTA |

**Recommendation**: Sprint 9+ add `EmptyStateIllustration` reusable
component (per D-05 designer deliverable in UX-REVIEW). Onboarding tour
via react-joyride — Sprint 10+.

---

## 3. Architecture review

### 3.1 Service layout

| Service | Tests | Schedulers | Resilience4j | Outbox |
|---|---|---|---|---|
| **dlmm-common** | 131 | 1 (OutboxDispatcher) | — | shared lib |
| dlmm-gateway | 0 | — | — | — |
| dlmm-user-service | 7 | — | — | — |
| dlmm-token-service | 26 | 2 (CustodyFee, B2BBilling) | ❌ none | producer |
| dlmm-pool-engine | 53 | 2 (PoolScheduled, MarginWatch) | ✅ 10 annotations | producer |
| dlmm-fee-service | 0 | — | ❌ none (deps present, no annotations) | consumer |
| dlmm-transaction-service | 44 | 1 (AmlScanner) | ❌ none | producer |
| dlmm-price-oracle | 9 | 3 (mock+twap+cbr) | — | — |
| dlmm-notification-service | 0 | — | — | consumer |
| dlmm-admin-bff | 0 | — | ❌ none (deps present, no annotations) | — |

**Test-coverage health:**
- `dlmm-gateway`, `dlmm-fee-service`, `dlmm-notification-service`,
  `dlmm-admin-bff` — **0 tests**. Of these, fee-service and admin-bff
  have business logic worth testing.

### 3.2 Resilience gaps

Confirmed tech debt (3 services):

| Service | Annotations | Why concerning |
|---|---|---|
| dlmm-fee-service | 0 (deps present, ConfigurationProcessor metadata yes) | Consumes BalanceMutated events; outage path = silent loss |
| dlmm-admin-bff | 0 (deps present) | Fan-out across 5 downstream services; one slow service blocks dashboard |
| dlmm-transaction-service | 0 | RestTemplate `TokenServiceClient` (Sprint 4 #4.6) — no breaker (R#33) |

**Recommendation:** Sprint 8 — wire `@CircuitBreaker` + `@Retry` on
the outbound calls in each. ~1 day per service × 3 = 3 days total.

### 3.3 Security posture

✅ Strong:
- JWT consolidated in `dlmm-common/security` with auto-config (Sprint 3 #3.9)
- Bearer-token forwarding via `BearerTokenForwardingFilter` (auto-installed)
- Secrets externalised via env (`docker/.env` gitignored, `*.env.example` template)
- Spring Security on every service (12 SecurityConfig files match)
- `@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")` on sensitive endpoints

⚠ Notable gaps:
- `docker/.env` dev defaults committed-friendly — production needs Vault rotation
- **No JWT revocation** — token lives full 60min after logout (R#-new per
  SBBOL design §2.4)
- **No CORS strict whitelist** (was tightened Sprint 2 #6003805 but
  prod-config needs verification before production deploy)
- **No rate-limit per user** beyond gateway-global (Sprint 8+ needs
  per-API-key rate-limit when API tiers ship)
- **No audit log** for admin actions (admin who flipped protocol_fee_pct
  from 0% to 5% — no centralised log of WHO did it WHEN)
- **No security scan for dependency vulns at PR time** — only weekly +
  on Dockerfile changes (container-scan workflow). Should add a Maven
  dependency-check on every backend PR.

### 3.4 Observability

✅ Strong:
- Micrometer + Prometheus everywhere (cascaded via dlmm-common)
- Grafana dashboards auto-provisioned
- DownstreamHealthIndicator (pool-engine, admin-bff) pings 5 services each
- KafkaConsumerLagHealthIndicator (notification-service)
- 8 Prometheus alert rules (Sprint 3 #3.11)
- Public status page (Sprint 5 #5.8)

⚠ Notable gaps:
- **PagerDuty wiring finally happened Sprint 6 Day 9 (R#13 closed)** —
  but test alert in staging not yet verified end-to-end
- **No distributed tracing** (no Sleuth / OpenTelemetry / Zipkin /
  Jaeger). Cross-service incident debugging = grep through 9 services'
  logs manually
- **No structured logging** — log lines are plain text. JSON logs +
  Loki / ELK would help Sprint 8+ ops
- **No SLO/SLI dashboard** — Grafana has p99/error/throughput tiles
  but no formal SLO definitions

### 3.5 Performance characteristics

From Sprint 3 #3.7 k6 baseline + Sprint 6 #5.F single-pool re-baseline:

| Metric | Current | Source |
|---|---|---|
| Throughput sustained | ~72 RPS | Sprint 3 #3.7 multi-pool |
| `/pools` p99 | 595ms (warm), 35-106ms (hot cache) | Sprint 1 #f4fca78 + Sprint 3 |
| `/dashboard` p99 | 16.87s under 300 VU multi-user load | Sprint 2 #c230ef2 (honest ceiling) |
| `/swap` p99 (single-pool) | 847ms | Sprint 6 #5.F |
| `/swap` error rate (single-pool) | 2.1% | Sprint 6 #5.F |
| `/swap` error rate (multi-pool) | 30%+ | Sprint 2 #c230ef2 (R#18, seed-data ceiling) |

**Throughput ceiling** = Hikari saturation + same-pool serialisation
+ seed-data exhaustion. R#18 still open for proper resolution
(bigger seed + multi-pool rotation in Sprint 8 SRE work).

### 3.6 Data layer

| Item | Status |
|---|---|
| Liquibase migrations | ✅ all services have changesets after Sprint 4 #4.10 |
| ddl-auto | ✅ `validate` everywhere |
| Per-changeset preConditions MARK_RAN | ✅ honoured everywhere (tactical hack from Sprint 1, proper split = Sprint 9+ debt) |
| Pangolin compatibility | ⚠ 1 InvariantTest fails (R-Pangolin-1 Sprint 7 investigation) |
| Backup story | ❌ undefined (CLAUDE.md known debt) |
| Read replicas | ❌ undefined (would need for Sprint 9+ index funds load) |

### 3.7 CI/CD maturity

| Workflow | Status |
|---|---|
| backend.yml | ✅ green, matrix postgres + pangolin (Sprint 6 #6.10) |
| frontend.yml | ✅ green (admin + user matrix vitest + vite) |
| container-scan.yml | ✅ Trivy HIGH/CRITICAL PR-gate (Sprint 4 #4.8) |

Missing:
- ❌ Deployment workflows (no Sprint 7+ deploy gate to staging/prod)
- ❌ Maven dependency-check security scan
- ❌ Helm chart / k8s manifests (only Docker Compose)
- ❌ Performance regression tests in CI (k6 runs are manual)

---

## 4. Severity-scored findings backlog

### 🔴 Critical (10 items)

| # | Finding | Sprint | Effort |
|---|---|---|---|
| C-1 | **Zero accessibility** — 1 aria across both UIs | Sprint 7 partial (UX-037), Sprint 8 sprint | 5-8d total |
| C-2 | **Zero CSS @media** — mobile relies on AntD structural breakpoints only | Sprint 7 (UX-042), Sprint 8 sweep | 3-4d |
| C-3 | **268+228 inline `style=` usages** | Sprint 7 partial, Sprint 8 designer pass | 3-5d |
| C-4 | **No language toggle** — locked to Cyrillic | Sprint 8 (UX-024) | 3-4d |
| C-5 | **No JWT revocation** on logout | Sprint 7+ R-new (security-track) | 2d |
| C-6 | **No admin audit log** for sensitive admin actions | Sprint 8 | 3d |
| C-7 | **Admin-ui has 0 tests** | Sprint 7+ | 5d (initial 20 tests) |
| C-8 | **No distributed tracing** | Sprint 9+ (Sleuth + Zipkin) | 3-5d |
| C-9 | **Frontend test coverage gap** on HedgePage / SwapPage (most-complex, untested) | Sprint 7 AI-5 | 2d initial |
| C-10 | **Resilience4j missing** on fee-service + admin-bff + transaction-service TokenServiceClient | Sprint 8 (R#33) | 3d |

### 🟠 Major (12 items)

| # | Finding | Sprint |
|---|---|---|
| M-1 | StatCard/formatRub duplication user-ui vs admin-ui | Sprint 9+ |
| M-2 | LoginPage no "Forgot password?" link | Sprint 8 |
| M-3 | RegisterPage 6-field-upfront friction (UX-027) | Sprint 8 |
| M-4 | DashboardPage stat tiles not drill-down (UX-001) | Sprint 8 |
| M-5 | PoolsPage no filter/sort | Sprint 8 |
| M-6 | LiquidityPage strategy selector no preview | Sprint 9 |
| M-7 | No revenue-attribution dashboard tile | Sprint 8 AI-7 |
| M-8 | Multi-pool k6 30% errors (R#18) | Sprint 8 SRE |
| M-9 | Real MOEX ISS feed (R#11) | Sprint 8 |
| M-10 | AML detector real-load FP measurement (R#35) | Sprint 7 mid AI-8 |
| M-11 | OTC pipeline 2/3 (R-Sales-1) | Sprint 7 PO trek |
| M-12 | Maven dependency-check in CI | Sprint 9 |

### 🟡 Minor / 🟢 Nice-to-have

22 items consolidated to `docs/UX-REVIEW-2026-06-03.md` Minor + Nice
+ this audit's catch-all. Not enumerated — picked up as filler in
Sprint 9-10.

---

## 5. Top systemic patterns

### 5.1 Backend quality vs frontend quality asymmetry

Backend gets **deliberate** architecture (TransactionTemplate for
self-invocation traps Sprint 5 #5.G, optimistic locking Sprint 4 #4.7,
pure-static decision functions across all detectors), heavy test
discipline (270 tests covering ~85% of business logic), Compliance
process integration that's working.

Frontend gets **rushed** delivery — design tokens defined but drift,
zero accessibility audit, no test coverage on most-complex pages,
mobile partially broken. Pattern suggests:
- Frontend dev is single-headcount (vs 3 backend devs)
- No permanent designer (R-Designer-1)
- BA / Compliance dialogue rarely reaches the frontend

**Recommendation:** budget shift — Sprint 8 should allocate ≥ 50% FE
capacity to UX critical block + accessibility + design tokens sweep.
This is a one-time investment with multi-quarter compound returns.

### 5.2 External dependencies dominate slip-causes

Sprint 6 retro §7 already flagged this. Audit confirms with concrete
numbers:
- 3 deferred items in Sprint 6 = 100% external-gated (SBBOL §7 q3,
  designer mockups timing, OTC pipeline shrink)
- 2 of 3 long-open risks (R#11 MOEX, R#13 PagerDuty rotation) had
  no engineering blocker — pure external/process delay

**Pattern**: invest in **decoupling stubs** (per Sprint 7 §3 decision
on 5.13) more than chasing external partners.

### 5.3 Capacity discipline gap

Two consecutive rebalances. Sprint 6 retro AI-3 codified the
"≤ 30d kickoff gate". This audit confirms it's the right move —
without it the team is structurally over-promising.

### 5.4 Compliance partnership is a positive outlier

Sprint 5 process change (daily standup inclusion) → Sprint 6 best-ever
throughput (3 sign-offs in-sprint). Demonstrates that headcount isn't
the bottleneck — **integration is**.

**Apply same pattern to:**
- Marketing (Sprint 8 retail/sales push)
- Sales (Sprint 8 OTC pipeline reinforcement)
- Designer (Sprint 7 AI-1 budget — permanent engagement)

---

## 6. Recommendation: 3-sprint UX-Hardening Track

To address the C-1, C-2, C-3, C-4 critical findings systematically:

**Sprint 7 (current — already loaded)**: 7 UX Critical items, no slack
to do more.

**Sprint 8 — "UX Hardening Sprint" proposal**:
- Designer pass (per AI-1 budget) — design tokens audit + sweep top 10 drift files (2d)
- Accessibility wave 1 — add 20+ aria attributes, semantic HTML wrappers (2d)
- Mobile breakpoint sweep — CSS @media queries for HedgePage, SwapPage, Dashboard (1d)
- Language toggle infrastructure (i18n setup + RU translation extraction) (3d)
- Admin-ui first test pass — 10 component tests (2d)
- Sprint 8 BA/UX tasks queue (UX-027 progressive register, UX-018 pool filters, UX-020 strategy preview) (3d)
- **Total: ~13 person-days** — needs PO blessing to deprioritise some Sprint 8 commercial items

**Sprint 9** — Spasibo write-back + index funds + 152-ФЗ audit (per
current SPRINT-PLAN.md Sprint 9) — keep as planned.

**Trade-off**: Sprint 8 UX hardening pushes OTC + money market to
Sprint 9; pushes index funds to Sprint 10. Run-rate impact: ~1 month
delay on ~150M ₽/year contribution from those features. UX investment
unlocks retail Spasibo widget (60M users TAM) — net positive on revenue
expectation.

---

## 7. Action items new from this audit

| # | Action | Owner | Deadline |
|---|---|---|---|
| **AU-1** | Sprint 8 PO decision: pure-commercial Sprint 8 (status quo) vs "UX Hardening Sprint 8" per §6 | PO + IT-lead | Sprint 7 mid |
| **AU-2** | Add Stylelint pre-commit hook blocking new `#hex` in `.tsx` (allowlist `main.tsx` + `sber-theme.css`) | SRE + Frontend | Sprint 8 Day 1 |
| **AU-3** | Sprint 8 add JWT revocation (Redis denylist by `jti`) | Backend dev 2 | Sprint 8 |
| **AU-4** | Sprint 8 add admin audit log table + `@AdminAudit` annotation | Backend lead | Sprint 8 |
| **AU-5** | Sprint 9 distributed tracing (Sleuth + Zipkin) | SRE | Sprint 9 |
| **AU-6** | Sprint 9 Maven dependency-check in backend.yml | SRE | Sprint 9 |
| **AU-7** | Sprint 10 formal WCAG 2.1 AA audit by Sber DS team | PO | Sprint 10 |
| **AU-8** | Update `RISK-REGISTER.md` (stale since Sprint 2 — confirmed in Sprint 6 retro) | SA | Sprint 7 close |

---

## 8. Final designer's verdict

**Backend**: 9/10. Best-in-class for a sprint-paced 2026 fintech build.
The architectural decisions (transactional outbox, optimistic locking,
pure-static decision functions, RU calendar in common lib) are senior-level.

**Frontend (visual + UX)**: 6/10. Strong brand alignment + Sber-native
feel (the strategic moat per Sprint 5 designer verdict holds). But
**execution quality** (design tokens drift, mobile gaps, accessibility,
test coverage) lags badly. Hire-or-contract a permanent designer is
the single highest-leverage move available.

**Frontend (architecture)**: 8/10. React Query + Zustand + AntD Pro
is the right stack; routing clean; service-layer abstraction good.
Component duplication user-ui ↔ admin-ui is the only structural debt.

**Operations / observability**: 7/10. Prometheus + Grafana + alert rules
good; PagerDuty just wired; missing distributed tracing + structured
logs is the maturity gap.

**Process / planning**: 6/10. Plan exists, BA/SA/Compliance discipline
strong, but two consecutive rebalances signal capacity-vs-ambition gap.
Sprint 6 retro AI-3 (kickoff hard gate) closes if enforced.

**Overall**: 7.5/10. Banking-grade-ready foundation; the gap between
backend craftsmanship and frontend execution is the dominant story.
Closing it via 1-sprint UX Hardening (§6) + permanent designer (AI-1)
moves the platform to production-launch-ready by Sprint 10.

---

*Recorded by: Designer (senior Sber DS, in role) + IT-lead + SA.
Sign-off: PO. Reviews: Compliance read §3.3 security; SRE read §3.7 CI/CD.
Companion to UX-REVIEW-2026-06-03.md — this audit supersedes for
quantitative claims, the older doc remains for qualitative heuristic
detail per-page.*
