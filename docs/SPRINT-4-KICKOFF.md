# Sprint 4 — Kickoff

**Date**: 2026-05-17 (immediately following Sprint 3 acceptance)
**Window**: 2 weeks
**Theme**: "FX hedges pilot + B2B settlement v1"
**Capacity**: 3 backend + 1 frontend + 1 SRE + 1 SA = ~6 task slots

> Plan source: `docs/SPRINT-PLAN.md` Sprint 4 section.
> Risk feed-ins: R#19–25 from monetization planning.

---

## Sprint goals (PO calls these out)

1. **First revenue from corp FX hedges.** 1+ pilot client onboarded, at
   least 1 settled hedge by sprint close. Even a fake-money sandbox
   client counts — we just need the workflow end-to-end.
2. **B2B settlement endpoint MVP.** SRUB corp-to-corp transfer at 5 bps,
   demonstrable inside the dev stack. SBBOL integration is design-only
   in this sprint.
3. **Same-pool row lock fix landed.** Closes R#20 before any of the
   above can serve real volume.
4. **Sber Treasury LP onboarding starts.** PO + CFO meeting, first 100M
   pilot LP placed (not necessarily real money — even a paper position
   counts for sprint).

If 1+3+4 land, Sprint 4 succeeded. 2 is the strategic-pillar bonus.

---

## Backlog (from SPRINT-PLAN.md + carry-over from Sprint 3)

### Code work

| # | Task | Source | Owner | Effort | Status |
|---|---|---|---|---|---|
| 4.1 | FX hedge corp UI on user-ui — calculator, quote, execute, settlement report download | M#3 | Frontend | 5d | Pending |
| 4.2 | Counterparty exposure limits in pool-engine (per-user max nominal, configurable per pool) | M#3 dep | Backend lead | 4d | Pending |
| 4.3 | Margin-call basic logic — email/webhook when open hedge value moves >80% toward limit | M#3 dep | Backend lead | 3d | Pending |
| 4.4 | Settlement report API — `GET /api/v1/transactions/report?userId&from&to` returning CSV for corp accountant | M#3 dep | Backend lead | 2d | Pending |
| 4.5 | SBBOL integration design sketch (no code) — auth handoff + balance lookup | M#3 dep | SA + Sber integrations | 5d | Pending |
| 4.6 | B2B settlement endpoint prototype — `POST /api/v1/transfers/b2b` (P2P, fee 5bps, separate from swap path) | M#4 | Backend lead | 5d | Pending |
| 4.7 | **Same-pool row lock fix** — @Version optimistic locking + retry loop in SwapService (per Sprint 3 #3.10 memo, Option A) | Sprint 3 carry-over R#20 | Backend lead | 5d | **HIGHEST PRIORITY** |
| 4.8 | Container scan → required status check on PR | Sprint 2 carry-over | SRE | 1d | Pending |
| 4.9 | Sponsored pool placement — admin can "pin" a pool to top of /pools listing | M#12 | Backend + Frontend | 3d | Pending (stretch) |
| 4.10 | **Add Liquibase to transaction/fee/notification services + switch ddl-auto from `update` to `validate`** | Sprint 3 acceptance bonus | Backend lead | 3d | Pending (R#4-derivative cleanup) |

### Non-code (cross-functional, PO tracks weekly)

| # | Task | Source | Owner | Deadline |
|---|---|---|---|---|
| 4.A | Sber Treasury onboarding — first 100M ₽ pilot LP placed | M#2 | PO + Treasury BU + Backend (ops) | End of sprint |
| 4.B | Pilot client selection — 3–5 corp clients for FX hedge pilot | M#3 | PO + Corp Sales | Sprint mid |
| 4.C | SBBOL contract — start integration negotiation | M#3 dep | PO + Sber integrations | End of sprint |
| 4.D | PagerDuty / Alertmanager wiring — Prometheus alerts → on-call rotation | R#13 final close | SRE | End of sprint (blocked on PO nominating rotation members) |
| 4.E | First green CI build verified post-merge | Sprint 3 carry-over | SRE | Sprint day 1 |
| 4.F | Real MOEX ISS API access procurement | R#11 + Sprint 3 #3.5 carry | PO | End of sprint |

---

## Distribution (today's standup)

- **Backend dev 1** → 4.7 same-pool lock fix (priority), then 4.2 exposure limits
- **Backend dev 2** → 4.6 B2B settlement endpoint, then 4.10 Liquibase cleanup
- **Backend dev 3** → 4.3 margin call, then 4.4 settlement report
- **Frontend** → 4.1 FX hedge UI (depends on 4.2, 4.4 ready in week 2)
- **SRE** → 4.E (day 1 verify), 4.8 container scan gating, 4.D PagerDuty
- **SA** → 4.5 SBBOL design sketch
- **PO** → 4.A, 4.B, 4.C, 4.F + daily standup

---

## Sprint 4 acceptance criteria (PO's checklist for the close meeting)

- [ ] Same-pool row lock fix landed; k6 single-pool test shows linear
      scaling under contention (no more 33% swap errors from rotation)
- [ ] FX hedge UI page live in user-ui, executes a hedge end-to-end
      against the SRUB/SCNY pool
- [ ] Counterparty exposure limit blocks a hedge that exceeds config
- [ ] B2B settlement endpoint accepts an integration-test request and
      writes the right outbox event
- [ ] At least 1 corp client has used the FX hedge in test mode
- [ ] Sber Treasury has placed pilot LP (paper or real) in 1+ pool
- [ ] 3 services now using Liquibase (transaction, fee, notification);
      none using `ddl-auto: update`
- [ ] PagerDuty receives a test alert when HikariPoolNearLimit fires
- [ ] All Sprint 3 carry-overs closed

---

## Risks for this sprint (carried + new)

| # | Risk | Mitigation |
|---|---|---|
| R#20 | Sber Treasury LP triggers same-pool starvation | 4.7 lands first — gate 4.A behind it |
| R#21 | B2B settlement reg-category ambiguous (transfer vs swap) | 4.5 includes compliance sign-off on the chosen category before 4.6 build |
| R#22 | SBBOL integration delay from external BU | 4.1 has fallback: ivanov login works without SBBOL for pilot demo |
| 4-new | k6 swap_errors will remain 30%+ on multi-pool baseline due to seed-data ceiling, not infra | Bigger seed liquidity or rotate across all 22 pools in 4-end re-baseline (R#18 follow-up) |

---

## Day 1 starting tasks (pick now)

Immediate work for tomorrow morning standup:

1. **Backend dev 1** opens `docs/ANALYSIS-SAME-POOL-LOCK.md`,
   spikes Option A on `LiquidityPool` entity (@Version + retry loop in
   SwapService). PR by day 3.
2. **Backend dev 3** starts `dlmm-transaction-service` Liquibase wiring
   (analogous to what we did for token-service in Sprint 3 day 3).
3. **SRE** verifies first CI green build on the previous Sprint 3
   commits (`91b5eb8`), then starts container-scan gating.
4. **PO** schedules Treasury BU meeting + Corp Sales pilot-client
   shortlist call.
5. **SA** drafts SBBOL integration spec outline.

---

## What's parked, not in this sprint

Confirmed from `docs/SPRINT-PLAN.md` parking lot — these are NOT
Sprint 4:

- Tokenized bonds (needs legal lift, 2027)
- Collateralized lending (needs money market first)
- Derivatives (18+ months)
- White-label to other banks (strategic re-eval)
- Outbox auto-config without explicit @EntityScan (proven impractical
  in Sprint 3, accepted)

---

*Recorded by: SA. Owner of follow-up: PO. Sprint 4 acceptance meeting:
2 weeks from today.*
