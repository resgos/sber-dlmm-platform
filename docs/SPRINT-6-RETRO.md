# Sprint 6 — Retrospective

**Date**: 2026-06-17 (day after Sprint 6 acceptance, same as Sprint 7 kickoff)
**Format**: 4 columns — **Liked** / **Learned** / **Lacked** / **Longed for** (4L variant of Start/Stop/Continue, gives more nuance).
**Duration**: 60 minutes (45 collecting + 15 prioritising action items)
**Participants (10)**: PO, IT-lead, SA, BA, Compliance lead, SRE on-call, 3 Backend devs, Frontend lead, Designer (one-time engagement — *flagged in §3*).
**Ground rule**: facts > opinions; specific examples > generalisations; action items must have an owner + deadline.

> Context anchors:
> - Sprint 6 closed 9 of 11 code items + 5 of 6 cross-functional (`SPRINT-6-ACCEPTANCE.md`).
> - **Second consecutive rebalance** (Sprint 5 → Sprint 6 → Sprint 7) — flagged as systemic in SPRINT-7-KICKOFF §11.
> - Compliance signed off 3 separate items in-sprint — best Compliance throughput to date.
> - SBBOL §7 q3 → "Q4 2026" answer — Plan-B-of-Plan-B activated for Sprint 7.

---

## 1. Liked (👍 keep doing)

| # | Item | Submitted by | Why it works |
|---|---|---|---|
| L1 | **Compliance throughput** — 3 sign-offs landed in-sprint (3.1 protocol fee cap, 6.7 115-ФЗ scope, 6.9 AML readiness) | Compliance lead | Embedding Compliance in daily standup (Sprint 5 process change) is paying off. Compliance went from "weekly drag" to "daily partner". |
| L2 | **5.D ЦФА memo cascade** — Sprint 5 verdict unblocked Sprint 6 #6.C Атомайз memo, which unblocks Sprint 9+ ЦФА secondary market (M-29) | SA | Pre-emptive design memos save weeks of rework. ЦФА verdict in Sprint 5 means we entered Sprint 6 with the legal frame already settled. Apply to other domain unknowns (SPFS rail, ЕБС). |
| L3 | **Pure-static-function detector pattern** — AML detectors as `public static Optional<DetectionResult> detect...` — easy unit-test, easy review | SA + Backend lead | Same pattern as Sprint 4 #4.3 `MarginWatchService.decideEvent`. **Promoted to "required test pattern" in project test guide**. |
| L4 | **Plan-B preparation in kickoff** — Sprint 6 kickoff explicitly listed "if SBBOL §7 q3 slips, 5.13 moves to Sprint 7" — when it actually slipped, the move was 5-minute decision not a crisis | PO + IT-lead | Apply to every external-gated item in Sprint 7+. Don't wait until the gate fires to think about fallback. |
| L5 | **Per-bucket commit strategy** — Sprint 6 day 1 (3.1+3.2), day 2 (6.7+6.9), day 3 (6.10+6.14+6.15+6.16+6.7-FE) — each commit narratively coherent | Backend lead | Reviewers can read commit-by-commit; PR descriptions write themselves. **Keep doing.** |
| L6 | **Status page #5.8** — public-readiness day-0 made `«статус системы»` an actual selling point in Sprint 6 demo | PO + SRE | First time a "public ops" feature directly helped a sales conversation. Treasury BU lead specifically commented on it. |
| L7 | **Idempotency-key-prefix pattern** for hedge unwind (`hedge-` / `unwind-`) avoided a backend schema change | Frontend | Same pattern works for any "this-action-paired-with-that-action" UX. Sprint 7+ — apply to liquidity add/remove pairing. |

## 2. Learned (💡 insights worth capturing)

| # | Insight | From event | Take-forward |
|---|---|---|---|
| LR1 | **External-gated items WILL slip** — SBBOL §7 has slipped TWICE now (Sprint 5 Plan-A → 5 Plan-B → 6 day-11 response → 7 stub-against-defaults). Never block code on cross-functional answer | SBBOL §7 q3 saga | Sprint 7+ — every external-gated item starts with a fallback design (stub / mock / defer plan) in the kickoff doc. |
| LR2 | **Designer engagement model is broken** — D-01 mockups for Sprint 6 #5.6-FE arrived day 8, FE couldn't ship in-sprint. Pattern recurring (Sprint 5 had same issue with SpasiboWidget mockup) | Sprint 6 #5.6-FE defer | R-Designer-1 risk → permanent designer engagement budget needed. PO 7.C trek Sprint 7 day 1. Detail in §4 action items. |
| LR3 | **Two rebalances in two sprints = original SPRINT-PLAN over-ambitious** — Sprint 5/6 kept absorbing carry-overs that pushed out original scope | SPRINT-PLAN.md history | Sprint 8 kickoff must do **velocity-reality check** vs Sprint 7 actuals. If realized < 25d, multi-quarter plan slips: 2027 Q1 target 1.7-2.3B → 1.5-2B (still net-positive, more honest). |
| LR4 | **Pangolin compatibility is non-trivial** — even on "drop-in" forks. 1 InvariantTest failure on BIGINT divergence found Day 14 | #6.10 CI matrix | Don't promote Pangolin leg to mandatory until 4 consecutive green runs (Sprint 7+ gate). Don't promise Минцифры реестр Pangolin-compatibility certificate until R-Pangolin-1 closed. |
| LR5 | **Compliance throughput strength came from process change, not headcount** — Compliance is still 1 person; daily standup inclusion is the multiplier | L1 above | Apply same change to other partner roles: Marketing (next quarter), Sales (Sprint 8 OTC pipeline). |
| LR6 | **AML detector edge cases hide in test fixtures** — sliding-window logic for ROUND_AMOUNT_REPEATS initially missed "3 across 2h vs 3 within 1h" distinction; caught by review test | Backend dev 3 review | Code review of detector logic deserves **deliberate slowness**. Pair-program detector tests. |
| LR7 | **Sales pipeline shrinks faster than code can deliver** — OTC went from 3 prospects (Sprint 5 #4.B) to 2 (Sprint 6 close, one dropped to Tinkoff). Code-vs-pipeline timing matters | 6.B / R-Sales-1 | PO trek reinforcement for Sprint 7; if pipeline ≤ 2 by Sprint 8 kickoff, OTC scope cuts to "1 anchor client" not "3 institutional". |

## 3. Lacked (👎 what was missing)

| # | Lack | Impact | Severity |
|---|---|---|---|
| LK1 | **Permanent designer** — one-time engagement model causes recurring late-mockups problem | Sprint 5 widget mockup late, Sprint 6 D-01 late (5.6-FE defer), Sprint 7 5.6-FE now compressed | 🔴 Critical |
| LK2 | **Real-load AML detector validation** — synthetic test fixtures are clean, but real transaction patterns might be noisier (false positives) | AML scanner not validated against real-shaped data yet | 🟠 Major — Sprint 7+ Track 3 should include "false-positive rate" measurement |
| LK3 | **Sales pipeline visibility** — PO + Corp Sales own it, engineering finds out at sprint close. By then it's too late to adjust code scope | 6.B OTC pipeline shrinkage discovered Day 12 of 14 | 🟠 Major |
| LK4 | **Capacity discipline** — kept saying yes to "this small carry-over" until Sprint 7 was 80% over capacity at kickoff | SPRINT-7-KICKOFF.md required immediate second rebalance | 🔴 Critical (systemic) |
| LK5 | **No retro action items from Sprint 5 were tracked** — Sprint 5 retro existed only in heads; nothing in writing meant nothing got applied to Sprint 6 | Sprint 5 retro insights lost between sprints | 🟠 Major — fix with §4 below ALWAYS in retro doc |
| LK6 | **Frontend test coverage growing slower than backend** — 44 frontend tests vs 270 backend; Sprint 6 added 0 frontend tests despite 3 FE deliverables (6.7 panel, 6.14 mass-action, 6.15 deep-link) | UX regressions hard to catch | 🟡 Minor — Sprint 7 add 5+ FE tests during UX Critical block |
| LK7 | **No revenue-attribution dashboard** — protocol fee accrual happens in DB but admin team has no easy view of "how much money got created this hour/day" | Hard to demonstrate revenue impact at PO meetings | 🟡 Minor — Sprint 8 admin-ui addition (~1d) |

## 4. Longed for (🎯 wishes → action items)

| # | Wish | Action item | Owner | Deadline |
|---|---|---|---|---|
| **AI-1** | Permanent designer presence | PO submits budget request for half-time designer engagement Sprint 7-9 | PO | **Sprint 7 Day 1** |
| **AI-2** | Velocity reality-check ritual | Sprint 8 kickoff doc MUST include section: "Sprint 7 attempted X / realized Y / re-baseline plan if Y < 0.7X" | IT-lead + SA | Sprint 8 Day 0 |
| **AI-3** | Capacity discipline | Sprint kickoff template gains a **"Total person-days ≤ 30"** hard check. Items exceeding capacity get moved BEFORE kickoff is published, not at acceptance | IT-lead | Sprint 7 retro |
| **AI-4** | Retro action items must be tracked | All retro action items land as GitHub Issues with sprint label + assignee + due date. Sprint kickoff opens with status of previous retro AIs | SA (retro scribe) | Sprint 7 kickoff |
| **AI-5** | Frontend test coverage growth target | Sprint 7 must add ≥ 5 frontend tests during UX Critical block. Sprint 8 target +10 | Frontend lead | Sprint 7-8 |
| **AI-6** | Sales pipeline visibility for engineering | PO publishes weekly Sales pipeline snapshot (counts, status) to engineering Slack channel | PO | Sprint 7 Day 5 (first weekly) |
| **AI-7** | Revenue-attribution view | Sprint 8 backlog: admin-ui dashboard tile "Сегодня заработано (protocol fee + MM rebate + B2B fees + custody fee)" | Frontend + Backend dev 1 | Sprint 8 (~1d) |
| **AI-8** | AML false-positive rate measurement | Sprint 7+ Track 3 design memo must include "how do we measure FP rate before going to Росфинмониторинг feed?" | SA | Sprint 7 mid |
| **AI-9** | External-gated item fallback design in kickoff | Every external-gated item in Sprint 7+ kickoffs lists explicit Plan-A / Plan-B / Plan-C with trigger conditions | IT-lead | Sprint 7 kickoff template (already done — keep enforcing) |
| **AI-10** | Pair-program detector logic tests | Sprint 7-9 — any new detector / state-machine code reviewed by SA + author together (not async) | SA + relevant dev | Sprint 7+ ongoing |

---

## 5. Sprint 5 retro action items — status check

(First time we're explicitly tracking this — per AI-4 going forward.
Sprint 5 had informal retro, no written AIs — applying retrospective
audit:)

| What Sprint 5 retro discussed informally | Status Sprint 6 close |
|---|---|
| BA flagged hedge mass-action need | ✅ DONE Sprint 6 #6.14 |
| BA flagged margin alert deep-link need | ✅ DONE Sprint 6 #6.15 |
| BA flagged Spasibo write-back scope creep | ✅ DONE Sprint 6 #6.16 memo |
| SA flagged k6 single-pool re-baseline still pending | ✅ DONE Sprint 6 5.F |
| SA flagged Resilience4j on transaction-service TokenServiceClient | ❌ NOT DONE — still missing, R#33 added |
| SA flagged LpPositionRepository keyset pagination | ❌ NOT DONE — Sprint 8+ |
| Compliance flagged 152-ФЗ audit timing | ⏸ scheduled Sprint 9 |

**Hit rate ≈ 60%** — confirms LK5 problem (no written tracking →
items fall through). AI-4 closes this.

---

## 6. Risk register delta from retro

Added:

| # | Risk | Source | Mitigation |
|---|---|---|---|
| R#33 | Resilience4j missing on transaction-service TokenServiceClient (Sprint 4 #4.6 carry, surfaced in §5 audit) | Sprint 5 retro audit | Sprint 8 add when B2B traffic exists |
| R#34 | Sprint planning capacity ≠ realized capacity — pattern unsustainable | Multi-sprint pattern | AI-2 + AI-3 closes |
| R#35 | AML false-positive rate unknown on real-shape data | LK2 | AI-8 closes via Sprint 7 design memo |

---

## 7. Themes — what this retro is really about

Three patterns surface across the 4 columns:

1. **External dependencies are the dominant slip-cause.** SBBOL §7,
   designer mockups, Sales pipeline — all things engineering doesn't
   control, all dominating the actual deferrals. Pattern says: invest
   in **decoupling** (Plan-A/B/C, stubs, internal alternatives) more
   than in chasing the external partner.

2. **Process changes deliver multiplier value.** Compliance daily
   standup (Sprint 5 change) → 3 sign-offs in Sprint 6 = best ever.
   No headcount added, just process. Apply same logic to other partner
   roles.

3. **The original plan was over-ambitious — and we kept saying yes.**
   Two rebalances in two sprints. Need a kickoff template gate that
   says "if total > 30d, kickoff doesn't publish — move items first".

---

## 8. Decisions captured

- [✓] AI-1 — PO submits designer budget Sprint 7 Day 1 (commits to 50% allocation through Sprint 9).
- [✓] AI-2 — Sprint 8 kickoff template change.
- [✓] AI-3 — capacity discipline becomes hard gate.
- [✓] AI-4 — retro action items are tracked artifacts going forward.
- [✓] AI-5 — frontend test growth target encoded into Sprint 7-8 acceptance.
- [✓] AI-6 — PO weekly Sales pipeline snapshot.
- [✓] AI-7 — revenue dashboard tile added to Sprint 8 backlog.
- [✓] AI-8 — AML false-positive measurement in Sprint 7 SA memo.
- [✓] AI-9 — kickoff template gains fallback section (already in SPRINT-7-KICKOFF — keep enforcing).
- [✓] AI-10 — pair-program detector tests SA + author.

---

*Recorded by: SA (scribe). All 10 participants signed off on action items.
Next retro: Sprint 7 close (2026-07-01). AI-status reviewed at every
sprint kickoff per AI-4.*
