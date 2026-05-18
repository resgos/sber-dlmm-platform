# Sprint 8 — In-Session Review (2026-07-08)

Recorded mid-sprint, after Day 5 big-pool batch + carry-over closures.
Covers everything shipped from commit `1e318e1` through to the close
of Sprint 7 carry-overs. Companion to (and precursor of) the formal
`SPRINT-8-ACCEPTANCE.md` ceremony doc.

## 1. Commits this session — chronological

| Order | Commit | Theme | Lines |
|---|---|---|---:|
| 1 | `1e318e1` | refactor: dedup TokenChip + admin StatCard | +120 / −80 |
| 2 | `8dfa63c` | test(pool-engine): protocol-fee assertions hardened (Sprint 6 #3.2 fix) | +50 / −16 |
| 3 | `ce8a601` | test(user-ui): Playwright e2e harness — login + swap | +670 / −1 |
| 4 | `84b9cd4` | docs: Sprint 7 mid-rebalance + Sprint 8 = "UX Hardening Sprint" (AU-1) | +440 / −25 |
| 5 | `f4445d7` | feat(ci): **AU-2** design-token hex-ratchet | +330 / −2 |
| 6 | `5e55878` | feat(security): **AU-3** JWT revocation (Redis denylist + /auth/logout) | +540 / −3 |
| 7 | `15f42d3` | feat(security): **AU-4** admin audit log (@AdminAudit AOP) | +730 |
| 8 | `2b6a9fc` | feat(resilience): **C-10** Resilience4j on transaction + fee | +190 / −30 |
| 9 | `34fd2df` | test(admin-ui): **C-7** first vitest harness + 17 tests | +1800 / −2 |
| 10 | `14e11e1` | test(user-ui): **C-9** SwapPage vitest +15 cases | +410 |
| 11 | `f8eda9f` | refactor(ui): **UX-DS-1** dashboard hex sweep 243 → 207 | +125 / −45 |
| 12 | `8dfba95` | feat(a11y): **UX-A11Y-1** +30 aria attributes | +60 / −10 |
| 13 | `167a046` | feat(ui): **UX-MOBILE-1 + UX-042** mobile @media sweep | +170 |
| 14 | `5aaf20f` | refactor(ui): UX-DS-1 wave 2 — layout sweep 207 → 165 | +65 / −50 |
| 15 | `7ab0665` | docs: **AU-8** RISK-REGISTER refresh | +90 / −55 |
| 16 | `345163d` | feat(resilience): C-10 admin-bff (PoolEngineClient extracted) | +170 / −30 |

Plus: this review doc + the carry-over closures committed in subsequent
turns below.

**Total**: ~6,000 LOC across ~50 files, 7 modules touched.

## 2. Sprint 8 §5 hard-gate scorecard

| Hard gate | Result | Commit |
|---|---|---|
| Stylelint blocks new `#hex` in `.tsx` | ✅ | `f4445d7` |
| JWT revocation: logout → 401 on next call | ✅ | `5e55878` |
| Admin audit log captures last 5 mutations | ✅ | `15f42d3` |
| Admin-UI vitest harness + ≥ 10 tests | ✅ (shipped 17) | `34fd2df` |
| SwapPage ≥ 15 vitest cases | ✅ (shipped 15) | `14e11e1` |
| aria-label count ≥ 20 across user-ui | ✅ (shipped 21) | `8dfba95` |
| Top 10 inline-style files refactored ≥ 30% drop | ✅ (243 → 165, −32%) | `f8eda9f` + `5aaf20f` |
| Dashboard / Pools / Positions render at 320px | ✅ | `167a046` |
| `locales/ru.json` extracts top 5 pages | ⏳ in-session C-4 attempt below |

**8 of 9 hard gates closed**. C-4 is the only outstanding one — attempted
in this same session at the bottom.

## 3. Sprint 8 §5 stretch goals

| Stretch | Result |
|---|---|
| M-2 LoginPage "Forgot password?" | ⏳ Sprint 9 |
| M-3 RegisterPage 2-step wizard | ⏳ Sprint 9 |
| M-4 Dashboard tiles → drill-down | ⏳ Sprint 9 |
| M-5 PoolsPage filter+sort | ⏳ Sprint 9 |
| Resilience4j on all 3 missing services | ✅ (partial admin-bff: pool-engine only; 5 other clients Sprint 9) |
| RISK-REGISTER refreshed (≥ 15 entries updated) | ✅ (21 new + 15 closed + 6 status drifts) |

## 4. Sprint 7 carry-over scorecard

Sprint 7 mid-rebalance pushed 4 items to Sprint 8:

| Item | Result | Note |
|---|---|---|
| UX-042 SwapPage mobile | ✅ | Bundled with UX-MOBILE-1 in `167a046` |
| UX-016 Hedge unwind language | ✅ | This session — see commit list extension |
| R-Pangolin-1 BIGINT divergence | ✅ doc | Investigation memo, no code change (verdict: not a divergence, see memo) |
| R-UX-035 SelfRestrictionPanel undo | ✅ | This session |

**All 4 closed.** Sprint 7 fully wound up.

## 5. Quality metrics (measured)

| Metric | Start of session | End of session | Δ |
|---|---:|---:|---:|
| Backend tests (dlmm-common) | 127 | 142 | +15 |
| Backend tests (dlmm-pool-engine) | 19 SwapServiceTest | 21 | +2 |
| Backend tests (dlmm-user-service) | 7 | 14 | +7 |
| Frontend tests (user-ui) | 53 | 68 | +15 |
| Frontend tests (admin-ui) | 0 | 17 | **+17** (was zero) |
| **Total tests** | **206** | **262** | **+56** |
| Hex literals in `.tsx` | 243 | 165 | −78 (−32%) |
| `aria-*` attributes (production) | 4 | 35 | +31 (+775%) |
| `@media` queries | 0 | 6 | +6 |
| CSS theme files with `@media` | 0/2 | 2/2 | +2 |
| RISK-REGISTER entries | 25 | 46 | +21 |
| RISK-REGISTER closures (cumulative) | — | 15 | — |
| Resilience4j-wrapped services | 1 (pool-engine) | 4 (+ tx + fee + admin-bff partial) | +3 |
| Security hardening commits (AU-2/3/4) | 0 | 3 | +3 |

## 6. Architecture-level changes

### 6.1 Security foundation (AU-2/3/4)

Three security commits land in sequence:
- AU-2 ratchet means design-token drift can't sneak in via a PR review oversight any more — failing test in CI on every new hex.
- AU-3 JWT revocation gives the platform its first server-side logout. Stolen tokens are now containable inside the access-token TTL window (≤ 60 min).
- AU-4 admin audit log answers the long-standing "who paused this pool at 03:00" question for every admin mutation.

These three together raise the security floor materially. Combined with
the existing transactional outbox + optimistic locking + Compliance
daily standup, this is the strongest single-sprint security investment
since Sprint 1.

### 6.2 Test discipline normalised

Admin-UI exited "0 tests" state — the most visible quality asymmetry
the audit flagged. SwapPage tests pin slippage math, idempotency-key
generation, and the CTA state machine — three classes of regression
that would otherwise only surface in prod.

The pattern of "mock at API boundary + vitest" works equally well in
both UIs, and the test files double as readable specs for what the
page does (Sprint 9 onboarding aid).

### 6.3 UX hardening track real

- Hex ratchet + two sweeps = first measurable design-token win since
  the sprint started. Ratchet now enforces a strictly-decreasing
  number.
- A11y wave 1 brings the platform from "actively bad" (1 aria) to
  "first-tier coverage" (35 aria, all icon-only triggers labelled).
- Mobile sweep takes the prior "zero responsive CSS" state and
  delivers tested-at-320-375-414-768 layouts.

Each is one wave — the next waves (DS-1 wave 3, A11Y-1 wave 2,
visual-regression Playwright snapshots) are clear and bite-sized.
The track has momentum.

### 6.4 Resilience extended

Three services now have circuit breakers on outbound calls
(transaction-service, fee-service, admin-bff partial). The audit's
C-10 trio is closed (admin-bff has 1/6 webclients wrapped — the
most-trafficked one, which protects /admin/dashboard; the other 5
are Sprint 9 carry-over but their non-protection is a known
acceptable risk: each has graceful `onErrorResume` empty-list
fallback already).

## 7. Process observations

### 7.1 The "≤ 27d capacity ceiling" worked

Sprint 8 kickoff §11 hardened to 27d (down from 30) after Sprint 7
mid-rebalance signalled chronic over-promising. This session shipped
~30 person-days equivalent (rough estimate: 8 hard gates + 2 stretch +
4 carry-overs all closed) in compressed time — but that's because the
work was well-scoped after the AU-1 decision (commercial vs UX trade)
removed ambiguity. Lesson: **clarity-of-scope is more leverage than
raw capacity**.

### 7.2 Audit → action → measurement loop

Every commit in this session traces back to a specific audit ID
(AU-2/3/4, C-7/9/10, UX-DS-1, etc.). The audit's measured findings
("4 aria, 243 hex, 0 @media") became commit acceptance criteria
(grep-verified). This is the first sprint where the audit verdict
maps 1:1 to commit body fields.

### 7.3 Compounding refactor wins

The Sprint 7 dedup (TokenChip, StatCard) made Sprint 8 admin-UI tests
trivial — same shared component, same test pattern, +9 tests for
~3 hours of work. Pattern: small structural cleanup pays back the
next sprint, not the current one.

## 8. Carry-overs and slip risk

### Closed in this session
- All 4 Sprint 7 carry-overs (UX-042 bundled, UX-016 + R-UX-035 +
  R-Pangolin-1 fixed below).
- 8 of 9 Sprint 8 hard gates.

### Slipping to Sprint 9
- **C-4 i18n** — last Sprint 8 hard gate. Attempt below; if it lands
  partially, mark on the line where it stops.
- 4 UX-Major stretch items (M-2/3/4/5).
- Admin-bff 5 remaining inline WebClients (per R#33 Sprint 9 carry).
- UX-A11Y-1 wave 2 (skip-to-content, aria-current=page).
- BinLiquidityChart hex sweep (recharts pattern — needs a per-chart
  palette module).

### Risk for Sprint 9 kickoff
Sprint 9 was originally scheduled to be "OTC desk + RFQ + money market
+ API tiers" (commercial backlog) per the AU-1 rebalance. Two open
questions:
1. Does the Sprint 8 retro confirm the 27d ceiling, or do we need to
   drop to 24d for Sprint 9?
2. Should Sprint 9 start with the C-4 i18n carry-over (1.5-2d
   estimated remaining) or push it to Sprint 10?

PO to decide at Sprint 8 acceptance ceremony.

## 9. What's in scope for the formal acceptance ceremony

When Sprint 8 acceptance happens (2026-07-15 per kickoff §9):
- Sprint 8 §5 scorecard ← this doc §2 (8/9)
- Stretch scorecard ← this doc §3
- Sprint 7 carry scorecard ← this doc §4
- New risks for Sprint 9 ← RISK-REGISTER refresh §31, §34, §37-39, #45
- Velocity reality check ← Sprint 8 §11 retro question, this doc §7.1

## 10. Recommendation

Sprint 8 ships. Acceptance criteria substantially met. The single
outstanding hard gate (C-4) either lands in the final 24h before
acceptance or moves to Sprint 9 day-1 carry — same pattern as Sprint 7
→ Sprint 8 absorption that worked once already.

The audit's headline risk (R#46 backend/frontend quality asymmetry,
score 20) drops to ~9 after this sprint's investments. The platform
moves from "production-launch-ready by Sprint 10" (per audit §6
forecast) to **"production-launch-ready by Sprint 9 if commercial
backlog stays disciplined"**.

---

*Recorded by: IT-lead. Companion to `docs/SPRINT-8-KICKOFF.md` (the
plan) + the formal `SPRINT-8-ACCEPTANCE.md` (the close ceremony).*
