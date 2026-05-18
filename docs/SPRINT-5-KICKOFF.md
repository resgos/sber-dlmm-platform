# Sprint 5 — Kickoff

**Date**: 2026-05-19 (day after Sprint 4 acceptance).
**Window**: 2 weeks.
**Theme**: "SberSpasibo distribution + B2B portal v1 + RU-market fit foundations"
**Capacity**: 3 backend + 1 frontend + 1 SRE + 1 SA = ~6 task slots
+ cross-functional PO/Compliance trek.

> **Sources**:
> - `docs/SPRINT-PLAN.md` Sprint 5 section (full plan)
> - `docs/RU-MARKET-RESEARCH-2026-05-18.md` (RU-feature filtering)
> - `docs/SPRINT-4-ACCEPTANCE.md` (carry-overs)
> - `docs/SBBOL-INTEGRATION-DESIGN.md` §7 (blocking questions for 5.13)

---

## Sprint goals (PO + IT-lead callout)

1. **Big distribution lever LIVE**: SberSpasibo conversion end-to-end
   (60M users / TAM unlock). 1 real Spasibo user converts points → SRUB.
2. **B2B portal MVP visible**: issuer registration + KYB workflow +
   token creation form. First test issuer through full flow.
3. **RU-market-fit foundations laid**: ЦБ rates feed, banking calendar,
   1С export, НДС split — closes the "you don't look Russian enough"
   feedback from Treasury BU.
4. **259-ФЗ ЦФА memo accepted by Compliance** — verdict gates all
   downstream regulatory work (RU-R1/R2/R3/R4 cascade in Sprint 7+).
5. **Sprint 4 verification debts paid**: k6 single-pool re-baseline
   (4.7), hedge unwind UI (4.1 follow-up), margin alerts UI (4.3 follow-up).
6. **SBBOL OIDC read-only first cut** (Sprint 4 #4.5 design → code),
   gated on PO submitting §7 questions to Sber integrations BU on day 1.

If 1-4 land, Sprint 5 succeeded. 5-6 are demo-day bonus.

---

## Backlog (consolidated from SPRINT-PLAN.md + RU research + Sprint 4 carry-overs)

### Code work

| # | Task | Source | Owner | Effort | Notes |
|---|---|---|---|---|---|
| 5.1 | **SSPAS token** in tokens table, Spasibo mint authority | M#5 | Backend dev 1 | 2d | Day 1 start |
| 5.2 | **SSPAS/SRUB pool** seeded from Spasibo treasury | M#5 | Backend dev 1 | 1d | After 5.1 |
| 5.3 | **SberID → SSPAS bridge** — webhook accept, idempotent mint | M#5 | Backend dev 1 + Sber loyalty BU | 8d | Cross-team; needs 5.A done first |
| 5.4 | **Loyalty conversion API** — POST /spasibo/convert (0bps retail fee) | M#5 | Backend dev 1 | 3d | After 5.3 |
| 5.5 | **Spasibo widget** on user-ui (1-click "конвертировать") | M#5 | Frontend | 4d | Parallel to 5.4 |
| 5.6 | **B2B portal MVP** — issuer reg + KYB workflow + token-create form | M#11, D#3 | Frontend + Backend dev 2 | 8d | Major piece; can split frontend/backend |
| 5.7 | **Billing engine for B2B** — listing fee + retainer + volume % | M#11 dep | Backend dev 2 | 5d | After 5.6 scaffolds |
| 5.8 | **Public status page** at status.dlmm.sber-online.ru | M parking | SRE + Frontend | 4d | Demo asset; pairs with k6 re-baseline (5.F) |
| 5.9 | **ЦБ РФ rates feed** in price-oracle + admin "spread vs official" tile | RU-M1 | Backend dev 3 | 2-3d | Quick win, demo-day visual |
| 5.10 | **Russian banking calendar** — holidays table + `BankingCalendarService` + wire into custody fee (#3.3) + margin call (#4.3) | RU-D1 | Backend dev 3 | 2-3d | After 5.9 |
| 5.11 | **1С банк-клиент XML export** — extend Sprint 4 #4.4 with `?format=1c-xml` | RU-T3 | Backend dev 3 | 3-4d | After 5.10; pairs naturally with #4.4 author |
| 5.12 | **НДС split on B2B fees** — `vat_amount` column on `b2b_settlements`, fee config split, CSV/XML reflects | RU-T2 | Backend dev 2 | 2d | Sprint mid; compliance ask |
| 5.13 | **SBBOL OIDC handoff (read-only)** — `dlmm-common/auth/oidc` shared bus + balance lookup via new SbbolClient | Sprint 4 #4.5 | Backend dev 2 | 8d | **GATED on 5.E day-1 submission AND day-5 response landing** |
| 5.14 | **Hedge unwind UI** on user-ui — close-position flow for FX hedges | Sprint 4 #4.1 follow-up | Frontend | 1-2d | After 5.5 ships |
| 5.15 | **Margin-alert rendering** on user-ui — notification panel consumer | Sprint 4 #4.3 follow-up | Frontend | 2d | After 5.14 |

### Non-code work (cross-functional, PO tracks weekly)

| # | Task | Source | Owner | Deadline |
|---|---|---|---|---|
| 5.A | **Spasibo BU integration scope kickoff** | M open Q#3 | PO + Loyalty product head | Day 2 |
| 5.B | **Q3 OKR review** with PO + CFO (revenue baseline vs target) | M#1, M#6 | PO + CFO | Sprint mid |
| 5.C | **KYB workflow design** for B2B portal | M#11 dep | Compliance + UX | Sprint mid |
| 5.D | **259-ФЗ ЦФА classification memo** | RU-R5 | SA + Compliance | Sprint mid |
| 5.E | **SBBOL §7 submission** — formal letter to Sber integrations BU | Sprint 4 #4.5 | PO | **Day 1** — gates 5.13 |
| 5.F | **k6 single-pool re-baseline** (verifies Sprint 4 #4.7 optimistic lock under contention) | Sprint 4 acceptance | SRE | Sprint mid |
| 5.G | **Legal memo follow-up on 3.A** — chase compliance lead for protocol_fee verdict (blocked 6+ working days from Sprint 3) | Sprint 3/4 carry-over | PO + escalate to compliance head | Day 3 |

---

## Distribution (Day-1 standup)

- **Backend dev 1** → 5.1 → 5.2 → 5.3 → 5.4 (Spasibo lane, full sprint)
- **Backend dev 2** → 5.6 backend half → 5.7 → 5.12 → 5.13 (B2B + OIDC lane)
- **Backend dev 3** → 5.9 → 5.10 → 5.11 (RU-features lane — week 1)
  → spare capacity for 5.6 backend half OR 5.13 helper week 2
- **Frontend** → 5.5 → 5.6 frontend half → 5.14 → 5.15
- **SRE** → 5.8 → 5.F → support 5.6 deploy
- **SA** → 5.D (ЦФА memo) → SBBOL spec refinement based on 5.E response
- **PO** → 5.A, 5.B, 5.E (Day 1!), 5.G, daily standup, weekly Q3 OKR review

---

## Day-1 critical path (do BEFORE anything else)

These must start morning of 2026-05-19 or downstream tickets slip:

1. **PO → submit SBBOL §7 letter (5.E)**. Without this, 5.13 has nothing to be gated on. 30 min of work, then waits.
2. **PO → escalate 3.A legal memo (5.G)**. Compliance lead has been silent 6 working days. Cannot start Sprint 5 with this still mute.
3. **Backend dev 1 → Spasibo kickoff sync (5.A)** with loyalty BU. Without their webhook spec we can't even start 5.3 (the bridge).
4. **SA → 5.D ЦФА memo draft**. Independent of everything else — pure research/legal-reading. Doable solo. **Verdict by Sprint mid is on the critical path for Sprint 6 #6.C (Атомайз memo) AND Sprint 7+ Track 4 compliance cascade.**

---

## Sprint 5 acceptance criteria (PO's checklist for close meeting)

Code:
- [ ] 1 Spasibo user converts points → SRUB end-to-end (test mode is fine)
- [ ] B2B portal accepts 1 test issuer through KYB
- [ ] Billing engine generates first invoice (test)
- [ ] status.dlmm.sber-online.ru returns 200 OK with all 9 services
- [ ] ЦБ РФ rates spread tile live on admin dashboard
- [ ] Custody fee + margin-call sweep respect RU banking calendar (test: holiday, no accrual)
- [ ] /transactions/report supports `format=1c-xml` and import-tests clean in 1С банк-клиент v3.0
- [ ] B2B settlement response splits VAT from gross
- [ ] k6 single-pool baseline shows linear scaling (<5% retry-fail at 100 VU same-pool)
- [ ] SBBOL OIDC handles silent SSO for sandbox tenant (read-only mode)
- [ ] Hedge unwind UI lets treasurer close an open FX hedge in one click
- [ ] Margin-alert renders in user-ui notification panel when backend fires event

Non-code:
- [ ] 259-ФЗ memo (5.D) signed off by Compliance with explicit ЦФА / not-ЦФА / borderline verdict
- [ ] 3.A legal memo returned (then unblocks 3.1+3.2 as 1-day code change either this sprint or Sprint 6)
- [ ] SBBOL §7 questions submitted and at least q1+q2+q3 answered (5.E+5.13 gate)
- [ ] Q3 OKR review (5.B) produces revised target run-rate

---

## Risks for this sprint

| # | Risk | Mitigation |
|---|---|---|
| R-new-1 | SBBOL §7 response slow (>5 working days) blocks 5.13 from completing | Backend dev 2 has spare capacity from 5.6 (after frontend takes over)? Reassign to 5.12 + supporting 5.11 |
| R-new-2 | Spasibo BU webhook spec changes mid-sprint | 5.3 written against an interface, not a concrete class — re-glue if spec shifts |
| R-new-3 | 5.D ЦФА memo verdict is "yes, ЦФА" → Sprint 6 #6.7 (самозапрет) gets harder + RU-C3 + RU-R3 cascade re-prioritize | If verdict lands by Sprint mid, re-plan Sprint 6 in retrospective (or split into 6 + 6a) |
| R-new-4 | Pangolin (Sprint 6 #6.10) fails CI matrix → Track 2 stalls → Минцифры реестр submission (Track 3) loses one bonus point | Accept; Postgres community is still реестр-compliant indirectly via support |
| Carry-over R#13 | PagerDuty still not wired (Sprint 4 #4.D) | PO escalates rotation nomination on Day 1 standup |
| Carry-over R#11 | MOEX ISS feed not yet integrated (Sprint 4 #4.F) | 5.9 ЦБ РФ rates feed reduces dependency — we have OFFICIAL rates even without MOEX market quotes |

---

## What changed from "Sprint 5 in original plan" → this kickoff

Original Sprint 5 from SPRINT-PLAN.md was Spasibo + B2B portal + status page
(8 code tasks, 3 non-code). Today's kickoff adds **+4 RU-features (5.9-5.12)**,
**+3 Sprint 4 carry-overs (5.13-5.15)**, **+2 non-code items (5.D ЦФА memo, 5.F k6,
5.G legal-memo chase)**.

**Net capacity check:**
- Backend devs: 3 × 10 working days = 30 person-days available.
- Backend tasks total: 5.1(2) + 5.2(1) + 5.3(8) + 5.4(3) + 5.6backend(4) +
  5.7(5) + 5.9(3) + 5.10(3) + 5.11(4) + 5.12(2) + 5.13(8) = **43 person-days**.
- **Over-capacity by ~13 person-days.**

**Decision (made at planning):** **5.13 SBBOL OIDC (8d) is risk-buffered** —
its day-5 gate (PO must land SBBOL §7 response) realistically slips. If §7
response doesn't land by day 5, 5.13 drops to Sprint 6 and 13 person-days are
freed. Sprint 5 then fits cleanly at ~30 person-days.

**Plan-A**: §7 lands on time, 5.13 ships, sprint is tight but doable with overtime.
**Plan-B**: §7 slips, 5.13 deferred to Sprint 6, Sprint 5 is comfortable.

PO updates the schedule on day 5 standup based on §7 status.

---

## Demo-day shortlist (what we'll show at sprint close)

1. **Spasibo end-to-end** — live demo of "earn 1000 баллов → конвертировать в 100 SRUB → купить на SwapPage".
2. **B2B portal walkthrough** — new issuer registers a CORP-1 token, goes through KYB, sees billing invoice.
3. **ЦБ РФ rates tile** — admin dashboard shows DLMM market rate next to ЦБ official rate, spread highlighted.
4. **1С XML export** — download CSV vs 1С-XML, open both in respective tools live.
5. **Hedge unwind** — user closes an FX hedge from Sprint 4, gets balance back as SRUB.
6. **k6 single-pool linear scaling chart** — Sprint 4 #4.7 verification visible on Grafana.

Architecture-only:
7. **259-ФЗ ЦФА memo walkthrough** by SA (decision-tree explanation).
8. **SBBOL §7 status update** by PO (what answered, what's still blocking).

---

## What's parked, not in this sprint

Confirmed parking (from RU research + monetization strategy):

- Redis → Ignite migration (Strategic Track 1) — waits for activation trigger.
- ЕБС биометрия (RU-I3) — 6+ month integration.
- ФНС API auto-filing (RU-T4) — Sprint 7+ retail.
- ЭДО / Эквайринг (RU-D2/D3/S3) — Sprint 7+ when corp demand proves.
- Атомайз / Мастерчейн listing **memo** is Sprint 6 (#6.C); listing **code** is parked.
- Минцифры реестр phase-2+ — Track 3, multi-quarter PO work.

---

*Recorded by: IT-lead + SA. Sprint 5 acceptance ceremony: 2026-06-02.*
