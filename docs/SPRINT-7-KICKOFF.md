# Sprint 7 — Kickoff

**Date**: 2026-06-17 (day after Sprint 6 acceptance).
**Window**: 2 weeks.
**Theme** (post-second-rebalance): **"Sprint 6 carry-overs + MM rebate + B2B fee unlocks"**
**Capacity**: 3 backend + 1 frontend + 1 SRE + 1 SA = ~30 person-days
+ cross-functional PO/Compliance trek.

> **Second rebalance in two sprints — honest signal that original
> SPRINT-PLAN was too ambitious on multi-sprint stacking.**
> Sprint 6 acceptance §5 deferred 3 items into Sprint 7 day-1
> (5.13 + 5.6-FE + 6.8 = ~17d). Plus Sprint 6 rebalance moved
> OTC + MM block (~23d) here. Plus Revenue Research T1 picks
> (~10d). Plus UX Critical block (~4-5d).
>
> **Attempted Sprint 7 load: ~55d vs 30 capacity = 80% over.**
> This kickoff makes the cuts explicit and pushes the residual
> to Sprint 8/9 in the body of SPRINT-PLAN.md.

---

## 1. Source documents

| Doc | Purpose |
|---|---|
| `docs/SPRINT-PLAN.md` Sprint 7 section | Master backlog (now updated to reflect Sprint 8/9 overflow) |
| `docs/SPRINT-6-ACCEPTANCE.md` §1 | 3 deferred items (5.13, 5.6-FE, 6.8) + 6.E SBBOL §7 q3 status |
| `docs/REVENUE-RESEARCH-2026-06-03.md` §4 | T1 revenue picks for Sprint 7 absorb |
| `docs/UX-REVIEW-2026-06-03.md` §5 | UX Critical block (7 items, ~4-5d) |
| `docs/SBBOL-INTEGRATION-DESIGN.md` §2.3 | Defaults for stub-against-assumed-defaults approach (Sprint 7 5.13 strategic call below) |

---

## 2. Sprint goals (PO callout)

1. **3 carry-over items closed**: 5.6-FE (B2B portal frontend), 6.8
   (ЕСИА OIDC), 5.13 (SBBOL OIDC — stub-against-defaults approach,
   real config-flip when sandbox lands Q4).
2. **MM rebate program LAUNCHED**: 6.3-6.5 (rebate scheduler + tier
   table + onboarding) live; first MM receives first rebate by sprint
   close.
3. **Two T1 revenue unlocks**: B2B integration fees (d) on KYB-approve;
   NDS transparency badge (m) on every B2B invoice download.
4. **UX Critical block landed**: 7 items from UX-REVIEW that unblock
   accessibility + mobile + slippage chip + transactions drill-down.
5. **OTC + remaining MM + money market all move to Sprint 8** —
   explicitly call out the rebalance to set expectations.

If 1+2+3 land, Sprint 7 succeeded. 4 is UX track block. OTC pipeline
(6.B carry — 2 of 3 clients) gives PO breathing room — code in Sprint 8.

---

## 3. Strategic call: 5.13 SBBOL OIDC approach

**Context**: Sber integrations BU §7 q3 response says **Q4 2026 timeline
for sandbox tenant assignment** (Sprint 6 #6.E result). Sprint 5 already
activated Plan-B once; another defer to Sprint 9+ would leave SBBOL
auth path empty into Q4.

**Options:**

| Option | Pros | Cons |
|---|---|---|
| **(a) Stub-against-defaults** — implement against SBBOL-INTEGRATION-DESIGN §2.3 assumed OIDC issuer URL / scopes / token signing. Real config-flip via env vars when sandbox lands Q4. | 6.8 ЕСИА unblocks (shared auth bus ready); B2B portal sales pipeline gets working corp login Q3; tests against mock OIDC provider | Risk that real Sber OIDC differs from assumed defaults → 1-2d refactor in Q4 |
| **(b) Further defer to Sprint 9+** | Zero risk of refactor; cleaner | 6.8 ЕСИА blocks for another sprint; B2B portal sales has no corp SSO until Q4; loses Q3 sales momentum |

**Decision: (a) stub-against-defaults.**

Acceptance condition: the abstract OIDC plumbing in `dlmm-common/auth/oidc`
must be provider-agnostic; concrete `SbbolOidcProvider` ships with
config-flip path documented. ЕСИА #6.8 then plugs in as second provider
on the same bus. Real SBBOL config swap = 1-line `application.yml`
edit in Q4.

---

## 4. Backlog (post-second-rebalance)

### Code work

**Sprint 6 carry-overs (~17 person-days):**

| # | Task | Owner | Effort | Notes |
|---|---|---|---|---|
| **5.13** | SBBOL OIDC handoff — stub-against-defaults per §3 decision | Backend dev 2 | 8d | Shared bus + SbbolOidcProvider config-flip path. Tests against mock provider. |
| **5.6-FE** | B2B portal frontend (issuer self-service + admin KYB review) | Frontend | 4d | D-01 mockups in hand from Sprint 6 day 8. Day 1 start. |
| **6.8** | ЕСИА (Госуслуги) OIDC handoff — second provider on the bus | Backend dev 3 | 5-6d | Starts day 5 after 5.13 bus lands. Pairs naturally. |

**MM rebate launch — moved from Sprint 6 (~10 person-days, 23d original cut):**

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| 6.3 | MM rebate scheduler — daily top-10 LP, 80% protocol fee distribution | M#10 | Backend lead | 4d |
| 6.4 | MM tier table (Bronze/Silver/Gold) + config rebate % | M#10 | Backend | 2d |
| 6.5 | MM onboarding workflow + rebate report admin endpoint | M#10 | Backend + Admin UI | 4d |

**Revenue T1 unlocks (~3 person-days):**

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| **R-d** | B2B integration fee one-time charge on KYB-approve | Revenue research §4 | Backend | 2d |
| **R-m** | NDS transparency badge — visible on every B2B invoice download | Revenue research §4 | Frontend | 1d |

**UX Critical block — from UX-REVIEW §5 Sprint 7 row (~4-5 person-days):**

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| UX-003 | Transactions drill-down link from dashboard | UX-REVIEW | Frontend | 1d |
| UX-007 | Slippage visibility chip on SwapPage | UX-REVIEW | Frontend | 0.5d |
| UX-016 | Hedge unwind quote language fix | UX-REVIEW | Frontend | 0.5d |
| UX-037 | Price-impact accessibility (color + text) | UX-REVIEW | Frontend | 1d |
| UX-042 | SwapPage mobile breakpoint fix | UX-REVIEW | Frontend | 1d |
| UX-008 | Specific insufficient-balance error message | UX-REVIEW | Backend + Frontend | 1d |

**Sprint 6 acceptance follow-ups (~1 person-day):**

| # | Task | Owner | Effort |
|---|---|---|---|
| R-UX-036 | PositionsPage consume `?highlight={uuid}` query param (Sprint 6 #6.15 half-wired) | Frontend | ~1h |
| R-UX-035 | SelfRestrictionPanel "Cancel set" undo button | Frontend | ~3h |
| R-Pangolin-1 | Pangolin 1.5 BIGINT InvariantTest divergence investigation | SRE | 0.5d |

**Total Sprint 7 code: ~35 person-days vs 30 capacity = ~17% over.**
Acceptable stretch given carry-over absorption.

### Non-code work

| # | Task | Source | Owner | Deadline |
|---|---|---|---|---|
| 6.A-cont | First MM signed contracts → onboard in MM tier table | PO + Legal | Day 5 |
| 6.B-cont | OTC client onboarding (2/3 in pipeline) — reinforce, target 3 by sprint mid | PO + Corp Sales | Sprint mid |
| 7.A | Spasibo BU contract negotiation (per §6.16 memo open questions) | PO + Loyalty BU | 2w (Sprint 8 unblock dep) |
| 7.B | Sber integrations BU SBBOL §2.3 default confirmation — get them to sign off on assumed values | PO | Day 5 |
| 7.C | Designer engagement for Sprint 7-8 (per R-Designer-1) — PO budget decision | PO | Day 1 |
| 7.D | M-30 MM rebate pre-sell — Bronze/Silver/Gold tier waitlist outreach | PO + Sales | Sprint mid |

### MOVED out of Sprint 7

Explicit list to set expectations (zero ambiguity):

| # | Originally Sprint 7 | Moved to | Reason |
|---|---|---|---|
| **6.1** OTC desk admin workflow | Sprint 8 | OTC pipeline 2/3 — need full 3 before code lands |
| **6.2** RFQ API for VIP clients | Sprint 8 | Pairs with 6.1 |
| **6.6** API access paid tiers | Sprint 8 | Bundles naturally with M-33 Public Data API tiers |
| **7.1** Tokenized money market (YSRUB) | Sprint 8 | 8d task; Sprint 7 already 17% over capacity |
| **7.2** Yield distribution engine | Sprint 8 | Pairs with 7.1 |
| **7.3** Index basket token (SBER10) | Sprint 9 | Depends on 7.1 stable + Мастерчейн contract (Sprint 8) |
| **7.4** Index rebalance scheduler | Sprint 9 | Pairs with 7.3 |
| **7.5** Index dashboard user-ui | Sprint 9 | Pairs with 7.3-7.4 |
| **7.6** Reserve health for YSRUB | Sprint 9 | Pairs with 7.1 going GA |
| **R-h** CBR spread alert subscription | Sprint 8 | T2 revenue pick — 4d effort doesn't fit |
| **R-M-33** Public Data API tiers | Sprint 8 | Bundles with 6.6 |
| **R-M-24** LP onboarding concierge | Sprint 7+ PO trek | Zero code — manual service, ongoing |
| **R-M-30** MM rebate pre-sell | Sprint 7 PO trek (7.D) | Zero code |

---

## 5. Distribution (Day-1 standup)

- **Backend dev 1 (lead)** → **6.3 MM rebate scheduler (Day 1-4)** → 6.4 MM tier table (Day 5-6) → 6.5 onboarding workflow (Day 7-10) → support 5.13/6.8
- **Backend dev 2** → **5.13 SBBOL OIDC stub-against-defaults (Day 1-8)** — gate item.
- **Backend dev 3** → **R-d B2B integration fees (Day 1-2)** → **6.8 ЕСИА OIDC (Day 5-10, after 5.13 bus lands)** → R-Pangolin-1
- **Frontend** → **5.6-FE B2B portal (Day 1-5)** → UX Critical block (Day 6-10) — UX-003, UX-007, UX-016, UX-037, UX-042 + R-UX-035, R-UX-036, R-m NDS badge.
- **SRE** → R-Pangolin-1 investigation (Day 1) → R#13 PagerDuty wiring (long-overdue from Sprint 3) → MM rebate scheduler ops support
- **SA** → 7.B SBBOL defaults sign-off support → 6.8 ЕСИА mapping spec → Sprint 8 money market design memo
- **PO** → 7.A Spasibo contract, 7.B SBBOL defaults, 7.C designer budget, 7.D MM pre-sell, 6.B OTC reinforcement

---

## 6. Day-1 critical path

1. **PO → submit 7.C designer budget request** by EOD Day 1. R-Designer-1 risk close — without permanent designer Sprint 8 hits same D-01-late-arrival problem.
2. **Backend dev 2 → start 5.13 stub-against-defaults immediately**. This is the longest sequence-dependent item; 6.8 ЕСИА depends on the bus.
3. **Backend dev 1 → start 6.3 MM rebate scheduler** — pairs with 6.A trek (1 MM signed, 2 verbal-yes). First rebate fires sprint close.
4. **Frontend → start 5.6-FE B2B portal** with D-01 mockups in hand from Sprint 6 day 8. Day 5 ship target — then jump into UX Critical block.
5. **SRE → R#13 PagerDuty wiring** (PO rotation members nominated Sprint 6 Day 9, finally unblocked). 0.5d work.

---

## 7. Sprint 7 acceptance criteria

Day-1 commits:
- [ ] 5.13 SBBOL OIDC stub provider live in dev; ЕСИА #6.8 plugged in as second provider on shared `dlmm-common/auth/oidc` bus
- [ ] 5.6-FE B2B portal: issuer self-service registration form + admin KYB review screen live
- [ ] First MM (6.A signed contract) receives first rebate via #6.3 scheduler
- [ ] B2B issuer integration fee (R-d) charged on KYB-approve; visible on first invoice
- [ ] NDS badge (R-m) visible on every B2B settlement / invoice download page

MM rebate program:
- [ ] MM tier table populated (Bronze 5%, Silver 10%, Gold 15% rebate share — pricing TBD)
- [ ] Onboarding admin endpoint approves MM → tier assigned → first daily rebate scheduler tick fires

UX Critical block:
- [ ] Dashboard transactions drill-down link works (UX-003)
- [ ] Slippage chip visible on SwapPage (UX-007)
- [ ] Hedge unwind quote language softer (UX-016)
- [ ] Price-impact has aria-label / sr-only text (UX-037)
- [ ] SwapPage mobile (320-414px) renders cleanly (UX-042)
- [ ] Insufficient-balance error shows specific token + amounts (UX-008)

Cross-functional:
- [ ] 7.A Spasibo BU contract draft circulated
- [ ] 7.B Sber integrations BU sign-off on SBBOL §2.3 defaults
- [ ] 7.C designer engaged for Sprint 7-8 work
- [ ] 6.B OTC pipeline at 3 of 3 prospects
- [ ] R#13 PagerDuty receives test alert in staging

---

## 8. Risks for this sprint

| # | Risk | Mitigation |
|---|---|---|
| **R#32 carry** | SBBOL stub-against-defaults may diverge from real Sber OIDC → 1-2d Q4 refactor | 7.B PO trek to get sign-off on §2.3 defaults this sprint reduces Q4 refactor surface |
| **R-Sales-1 carry** | OTC pipeline 2 of 3 — Sprint 8 OTC code launches against shrinking pipeline | 6.B PO trek reinforce; if still 2 by Sprint 8 kickoff, OTC scope cuts to 1 anchor client |
| **R-Designer-1 carry** | No permanent designer; D-01 was day 8; Sprint 7 has 5.6-FE depending on existing mockups | 7.C PO budget Day 1; emergency contract designer if needed |
| **R-new-Sprint7-1** | Sprint 7 17% over capacity — UX block or revenue T1 picks might slip | Plan-B: UX Critical block items are individually small (0.5-1d each), cut last 2 if necessary |
| **R#11 long** | Real MOEX ISS feed not yet integrated | 4.F Sprint 8 procurement target |
| **R#18 long** | Multi-pool k6 baseline 30% errors from seed-data ceiling | Sprint 8 SRE work |
| **R-Pangolin-1** | Pangolin 1.5 InvariantTest BIGINT divergence | SRE investigation Day 1; fallback PgPro pinned version |

---

## 9. What's parked, NOT in Sprint 7

Confirmed not building Sprint 7 (explicit so no scope creep):

- **OTC desk + RFQ API** (6.1, 6.2) — Sprint 8 after pipeline at 3
- **API access paid tiers** (6.6) + Public Data API (M-33) — Sprint 8 bundle
- **Money market + index funds** (7.1-7.6) — Sprint 8 (money market) + Sprint 9 (index funds)
- **CBR spread alert subscription** (R-h) — Sprint 8
- **All Sprint 8+ revenue picks** (p, k, b, e, n, M-22, M-31, M-36) — Sprint 8/9 backlog
- **Spasibo write-back implementation** (#6.16) — Sprint 8+ gated on 7.A contract
- **152-ФЗ / 115-ФЗ / 161-ФЗ Track 3 compliance battery** — Sprint 8+ gated on 5.D verdict cascade
- **Минцифры реестр phase 2+** — Sprint 9+ multi-quarter PO trek

---

## 10. Companion artefacts

| Document | Purpose |
|---|---|
| `docs/SPRINT-PLAN.md` (updated this sprint) | Sprint 7-9 rebalance reflected; OTC/MM/money market/index funds reshuffled |
| `docs/SPRINT-6-ACCEPTANCE.md` | Sprint 6 close + carry-over context |
| `docs/SPRINT-7-KICKOFF.md` (this file) | Sprint 7 daily plan post-second-rebalance |
| `docs/SBBOL-INTEGRATION-DESIGN.md` §2.3 | Defaults used by 5.13 stub approach |
| `docs/REVENUE-RESEARCH-2026-06-03.md` | T1 picks (R-d, R-m) absorbed Sprint 7; T2 picks Sprint 8 |
| `docs/UX-REVIEW-2026-06-03.md` | Critical block absorbed Sprint 7; Major block Sprint 8 |

---

## 11. Sprint 7 retrospective question to track

**"Is the original SPRINT-PLAN overall too ambitious for our actual
team capacity?"**

Two sprints of rebalancing in a row suggests yes. Sprint 8 kickoff
must include a velocity-reality-check section based on Sprint 7
actuals: if Sprint 7 ships at < 25 person-days realized (vs 35
planned), the multi-quarter plan needs a slip to push 2027 Q1
revenue target from 1.7-2.3B to 1.5-2B (still net-positive vs Sprint 2
baseline but more honest).

---

*Recorded by: IT-lead + SA + PO. Sprint 7 acceptance ceremony: 2026-07-01.
Sprint 8 kickoff: 2026-07-02.*
