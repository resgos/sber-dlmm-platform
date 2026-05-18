# Sprint 8 — Kickoff

**Date**: 2026-07-02 (day after Sprint 7 acceptance).
**Window**: 2 weeks.
**Theme**: **"UX Hardening Sprint"** (per audit AU-1 decision).
**Capacity ceiling**: **27 person-days** (per Sprint 7 mid-rebalance velocity
reality-check — replaces the 30d default).

> **Why this sprint exists**: `SYSTEM-AUDIT-2026-06-17.md` measured
> backend quality at 9/10 and frontend at 6/10. Audit §6 proposed a
> one-sprint UX Hardening pause. AU-1 was the open decision; resolved
> **Option B (do it)** on 2026-06-23 after Sprint 7 mid-rebalance
> absorbed 3.5d of the same kind of work.
>
> Commercial backlog (OTC, MM, money market, API tiers) slid Sprint 8 →
> 9. See SPRINT-PLAN.md §Sprint 9-10 for the new ladder.

---

## 1. Source documents

| Doc | Purpose |
|---|---|
| `docs/SYSTEM-AUDIT-2026-06-17.md` §§3-7 | Findings inventory + AU-1 decision |
| `docs/UX-REVIEW-2026-06-03.md` §5 Major row | UX Major block tasks |
| `docs/SPRINT-7-MID-REBALANCE.md` | 4 carry-over items |
| `docs/CODE-REVIEW-2026-05-18.md` | Pattern context (dedup wins, test honesty, e2e) |
| `docs/SPRINT-PLAN.md` Sprint 8 section | Master backlog (updated this kickoff) |

---

## 2. Sprint goals (PO callout)

1. **Design-token drift stopped at the gate** — Stylelint `color-no-hex`
   pre-commit blocks any new `#hex` in `.tsx`. The 209-occurrence inventory
   from the audit becomes a strictly-decreasing number.
2. **JWT revocation live** — `/auth/logout` adds `jti` to Redis denylist;
   shared filter checks. Addresses audit C-5 (critical).
3. **Admin audit log live** — every sensitive admin mutation captured with
   actor + before/after diff. Addresses C-6 and the long-standing
   "we have no idea who paused this pool" gap.
4. **Admin-UI exits "0 tests" state** — vitest harness + 10 component
   tests. C-7 closed.
5. **SwapPage coverage ≥ 15 vitest cases** — the most-complex untested
   page gets first real coverage. C-9 closed.
6. **Accessibility wave 1 + mobile sweep + i18n foundation** — addresses
   C-1, C-2, C-4. Not "done"; first measurable progress on each.

If 1+2+3+4 land hard, Sprint 8 succeeded. 5+6 are the UX-Hardening core
delivery. Commercial slip noted, accepted, communicated.

---

## 3. Backlog

### 3.1 Sprint 7 carry-overs (~2.5d)

| # | Task | Owner | Effort |
|---|---|---|---|
| UX-042 | SwapPage mobile breakpoint fix | Frontend | 1d |
| UX-016 | Hedge unwind quote language fix | Frontend | 0.5d |
| R-Pangolin-1 | Pangolin BIGINT InvariantTest divergence | SRE | 0.5d |
| R-UX-035 | SelfRestrictionPanel "Cancel set" undo | Frontend | 0.5d |

### 3.2 Security / audit-track (~5.5d)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| **AU-2** | Stylelint `color-no-hex` pre-commit hook (allowlist: `main.tsx`, `sber-theme.css`, `theme.ts`) — fail PR if new `.tsx` introduces `#hex` | Audit C-3 + AU-2 | SRE + FE | 0.5d |
| **AU-3** | JWT revocation: Redis denylist by `jti`; `/auth/logout` adds to denylist with TTL=remainingExpiry; `JwtAuthenticationFilter` checks denylist | Audit C-5 + AU-3 | Backend dev 2 | 2d |
| **AU-4** | Admin audit log: `admin_audit_log` table (Liquibase) + `@AdminAudit` annotation + AOP advice capturing actor + method + args-hash + result | Audit C-6 + AU-4 | Backend lead | 3d |

### 3.3 Test coverage hardening (~5d)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| **C-7** | Admin-UI vitest setup (`vitest.config.ts` + `src/test/setup.ts`) + first 10 component tests: DashboardPage hero + StatCard render variants, UserList table rendering, PoolForm validation rules, LoginPage form + 401 alert | Audit C-7 | Frontend | 3d |
| **C-9** | SwapPage vitest coverage: slippage math (0.5%/1%/custom), token-flip preserves amount, MAX button fills balance, error+success alerts, quote refresh on amount change, disabled-states | Audit C-9 | Frontend | 2d |

### 3.4 Designer pass + accessibility + mobile (~5d)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| **UX-DS-1** | Design tokens audit + sweep top 10 inline-style files: replace `style={{ color: '#21A038' }}` with CSS class + variable. Measure: `style={` count drops ≥ 30% in those files | Audit §6 + AI-1 | Designer + FE | 2d |
| **UX-A11Y-1** | Accessibility wave 1: 20+ `aria-label` / `aria-describedby` / `role` attributes + semantic HTML wrappers on Dashboard, Swap, Pools, Login. Verified via grep count | Audit C-1 | FE | 2d |
| **UX-MOBILE-1** | Mobile sweep: CSS `@media` queries for Dashboard, Pools, Positions (Swap → UX-042, Hedge already responsive). Verified at 320/375/414px breakpoints | Audit C-2 | FE | 1d |

### 3.5 i18n foundation (~3d)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| **C-4** | `react-i18next` setup + AntD `ConfigProvider.locale` wired; extract Cyrillic strings from top 5 pages into `locales/ru.json`. Language toggle UI button stub-only (defaults locked to RU). EN translation = Sprint 9+ | Audit C-4 | FE | 3d |

### 3.6 UX-Major block wave 1 (~5d)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| M-2 | LoginPage "Forgot password?" link → POST `/auth/password-reset-request` (backend stub: 200 + Kafka event; real email sender = Sprint 9+) | Audit M-2 | BE + FE | 1.5d |
| M-3 | RegisterPage 2-step wizard: Step 1 = email + password; Step 2 = name + phone + KYC opt-in. Progressive disclosure cuts the 6-field-upfront friction (UX-027) | UX-027 + Audit M-3 | FE | 1.5d |
| M-4 | Dashboard stat tiles → drill-down: each tile becomes a `<Link>` to a filtered list (totalUsers → /users, activePools → /pools?status=ACTIVE, etc.) | UX-001 + Audit M-4 | FE | 1d |
| M-5 | PoolsPage filter + sort: token-name filter, status filter, APY/TVL ASC+DESC sort. Already 50% client-side (search exists); just expose more controls | Audit M-5 | FE | 1d |

### 3.7 SRE / observability (~2d)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| **AU-8** | RISK-REGISTER.md refresh: re-score 15+ stale entries, retire 5+ closed risks, add 5+ new (post-Sprint 7) | Audit AU-8 + Sprint 6 retro | SA | 1d |
| **C-10** | Resilience4j wiring on `dlmm-fee-service`, `dlmm-admin-bff`, `dlmm-transaction-service`'s `TokenServiceClient`. CB + retry + timeout per the pool-engine reference pattern | R#33 + Audit C-10 | Backend dev 3 | 1d (parallel) |

**Total Sprint 8 code: ~28 person-days vs 27 ceiling = +4% over.** Plan-B:
M-5 (PoolsPage filter+sort) is the smallest cut if velocity tightens.

### 3.8 Non-code

| # | Task | Source | Owner | Deadline |
|---|---|---|---|---|
| 8.A | OTC pipeline reinforce to 3/3 (Sprint 7 carry — Sprint 9 launch dep) | PO + Corp Sales | Sprint 8 close |
| 8.B | Money-market YSRUB legal frame (РепоТипо) — Compliance memo so Sprint 9 7.1 code lands ready | Compliance + Legal | 2 weeks |
| 8.C | Spasibo BU contract close (Sprint 7 7.A continuation) — gate for Sprint 9 write-back impl | PO + Loyalty BU | Sprint 8 mid |
| 8.D | Designer engagement formal contract (Sprint 7 7.C close) | PO | Day 1 |

---

## 4. Day-1 critical path

1. **SRE → AU-2 Stylelint hook by EOD Day 1.** Without it, every other
   inline-style file added Sprint 8 is permanent debt.
2. **Backend dev 2 → start AU-3 JWT revocation.** Has the most spec
   work (jti generation, Redis schema, filter integration); start ASAP.
3. **Backend lead → AU-4 admin audit log table + AOP scaffold.** Same
   reason as AU-3 — most spec-heavy item.
4. **Frontend → start UX-DS-1 + UX-A11Y-1 in parallel.** Designer
   handover from 8.D contract; meanwhile FE can begin a11y wave 1
   independently.
5. **PO → 8.D designer contract close.** Without designer engaged
   Day 1, UX-DS-1 slips and the whole sprint underdelivers on the
   "hardening" theme.

---

## 5. Sprint 8 acceptance criteria

**Hard gates** (must ship):
- [ ] Stylelint pre-commit blocks new `#hex` in `.tsx` (verified via test PR)
- [ ] JWT revocation: logout adds `jti` to Redis; revoked token → 401 on next call (integration test)
- [ ] Admin audit log captures last 5 admin mutations (DB query verified)
- [ ] Admin-UI vitest harness exists + first 10 tests green in CI
- [ ] SwapPage has ≥ 15 vitest cases (slippage, flip, MAX, error/success, quote refresh, disabled-states)
- [ ] aria-label count ≥ 20 across user-ui pages (grep-verified, was 1)
- [ ] Top 10 inline-style files refactored — `style={` count drop ≥ 30%
- [ ] Dashboard / Pools / Positions render cleanly at 320/375/414px width
- [ ] `locales/ru.json` extracts top 5 pages' Cyrillic strings; `ConfigProvider.locale` wired

**Stretch** (nice-to-have, cut first):
- [ ] All 4 UX-Major block items shipped (M-2/3/4/5)
- [ ] Resilience4j on all 3 missing services
- [ ] RISK-REGISTER refreshed (15+ entries updated)

---

## 6. Risks for this sprint

| # | Risk | Mitigation |
|---|---|---|
| **R-new-S8-1** | Designer slot from 8.D soft-committed → UX-DS-1 slips | Plan-B: FE does design tokens sweep without designer pass; designer review post-merge |
| **R-OTC-pipeline carry** | Sprint 9 OTC code launches against 2/3 pipeline if 8.A doesn't close | If still 2/3 by Sprint 9 kickoff, scope cut to 1 anchor client (deferred until first signed) |
| **R-commercial-delay** | ~1 month delay on OTC + money market revenue per audit §6 trade-off | Compensated by reduced FE bug-rate post-hardening; measure at Sprint 9 retro |
| **R-S8-velocity** | If Sprint 8 ships < 24d realised (vs 28d planned), 1+ stretch item cuts | Velocity reality-check moved from Sprint 7 retro (AI-3) — Sprint 8 retro must update the ceiling |
| **R-i18n-creep** | i18n is a long-tail problem; scoping risk that 3d explodes to 5d | C-4 acceptance criteria explicitly says "top 5 pages only, no EN translation" — push back on scope creep |

---

## 7. What's parked, NOT in Sprint 8

Confirmed not building Sprint 8 (explicit, no scope creep):

- **OTC, RFQ, API tiers, money market** (6.1, 6.2, 6.6, 7.1, 7.2,
  R-M-33) — Sprint 9 (per AU-1 trade-off)
- **Index funds + 152-ФЗ + reserve health + CBR spread** (7.3-7.6,
  R-h, 7.X) — Sprint 10
- **Tokenized bonds, derivatives, vault strategies** — parking lot
- **EN translation** — Sprint 9+ once C-4 RU extraction lands
- **Full WCAG 2.1 AA audit by Sber DS team** — Sprint 10 per audit AU-7
- **Distributed tracing (Sleuth + Zipkin)** — Sprint 9 per AU-5
- **Maven dependency-check** — Sprint 9 per AU-6

---

## 8. Companion artefacts

| Document | Purpose |
|---|---|
| `docs/SPRINT-PLAN.md` (updated this kickoff) | Sprint 8/9/10 ladder reflected |
| `docs/SPRINT-7-MID-REBALANCE.md` | Absorbed work + cuts narrative |
| `docs/SYSTEM-AUDIT-2026-06-17.md` | Source of all C-* and AU-* items |
| `docs/UX-REVIEW-2026-06-03.md` | Source of M-* and UX-* items |
| `docs/CODE-REVIEW-2026-05-18.md` | Pattern context for dedup + test honesty |

---

## 9. Sprint 8 retrospective questions to track

1. **Did UX Hardening Sprint reduce production FE bugs in next sprint?**
   (Baseline: Sprint 7 FE bug count; target: ≥ 30% reduction in Sprint 9.)
2. **Was the 27d ceiling honest?** If Sprint 8 ships > 28d or < 24d,
   recalibrate.
3. **Did stopping commercial backlog for one sprint hurt stakeholder
   confidence?** PO check-in at Sprint 8 mid + close.
4. **Is the designer engagement sustainable?** If 8.D is one-off contract,
   Sprint 9+ has same problem.

---

*Recorded by: IT-lead + Designer (newly engaged) + SA + PO.*
*Sprint 8 acceptance ceremony: 2026-07-15. Sprint 9 kickoff: 2026-07-16.*
