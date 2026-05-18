# Sprint 6 — Kickoff

**Date**: 2026-06-03 (day after Sprint 5 acceptance).
**Window**: 2 weeks.
**Theme** (post-rebalance): "Compliance core + Sprint 4/5 carry-overs + B2B portal frontend"
**Capacity**: 3 backend + 1 frontend + 1 SRE + 1 SA = ~30 person-days
+ cross-functional PO/Compliance trek.

> **Rebalanced 2026-06-02** per Sprint 5 acceptance §5: OTC desk + MM
> rebate block (originally 23d of Sprint 6) moved to Sprint 7 to keep
> capacity under 30d. Sprint 6 now focuses on compliance, carry-overs,
> and the sales-pre-empted B2B portal frontend.
>
> **Source documents:**
> - `docs/SPRINT-PLAN.md` Sprint 6 section (full plan, post-rebalance)
> - `docs/REVENUE-RESEARCH-2026-06-03.md` (Sprint 7+ commercial intake)
> - `docs/UX-REVIEW-2026-06-03.md` (UX critical fixes for Sprint 7 block)
> - `docs/SPRINT-5-ACCEPTANCE.md` §5 (carry-overs + Plan-B context)

---

## Sprint goals (PO callout)

1. **Day-1 commitments closed** — 3.1+3.2 (protocol fee toggle), 5.F
   (k6 single-pool run), 5.13 (SBBOL OIDC once §7 q3 lands), 5.6-FE
   (B2B portal frontend) all shipped.
2. **RU compliance core layered in** — самозапрет, ЕСИА OIDC, AML
   pattern alerts, Pangolin CI matrix. Each closes a regulatory
   pre-requisite for Sprint 7+ Track 3 work.
3. **Sprint 7 OTC+MM block pre-flighted** — cross-functional trek
   (6.A first MM contracts, 6.B first OTC clients) ripens contracts
   during Sprint 6 so code lands ready-to-launch in Sprint 7.
4. **Atомайз / Мастерчейн listing memo verdict** — Sprint 6 #6.C SA
   memo gates the M-29 (ЦФА secondary market) Sprint 9+ go/no-go.
5. **Минцифры phase 1 form submitted** — multi-quarter PO trek starts.

If 1-3 land, Sprint 6 succeeded. 4-5 are strategic-pillar prep.

---

## Backlog (post-rebalance, consolidated)

### Code work

**Day-1 commits (~14 person-days):**

| # | Task | Owner | Effort | Notes |
|---|---|---|---|---|
| **3.1** | Activate `protocol_fee_pct = 5%` per pool + admin endpoint to tune | Backend lead | 1d | Blocked since Sprint 3, unblocked 3.A legal memo (Sprint 5 #5.G). Pool entity already has `protocol_fee_pct` column. |
| **3.2** | Protocol fee distribution split (LP 95% / protocol 5%) | Backend lead | 1d | Add `total_protocol_fee_x/y` columns + wire in fee accumulation path |
| **5.F-run** | k6 single-pool re-baseline run on staging | SRE | 0.5d | Numbers attach to commit; if thresholds violated → R#28 promoted |
| **5.13** | SBBOL OIDC handoff (read-only) | Backend lead | 5-8d | Gated on §7 q3 landing (sandbox tenant timeline); Plan-B if slips → Sprint 7 |
| **5.6-FE** | B2B portal frontend (issuer registration + admin KYB review) | Frontend | 4d | Designer mockups D-01 due day 1 (UX-030) |

**RU compliance core (~14 person-days):**

| # | Task | Owner | Effort |
|---|---|---|---|
| 6.7 | Самозапрет (115-ФЗ amendment 2024) | Backend + Frontend | 3-4d |
| 6.8 | ЕСИА (Госуслуги) OIDC handoff | Backend | 5-6d |
| 6.9 | AML pattern-detection alert (proactive) | Backend | 3-4d |
| 6.10 | Pangolin / PgPro CI matrix test | SRE | 1-2d |

**BA-flagged Sprint 5 acceptance enhancements (~3 person-days):**

| # | Task | Owner | Effort |
|---|---|---|---|
| 6.14 | Hedge "Закрыть всё" mass-action | Frontend | 1d |
| 6.15 | Margin-alert deep-link to position page | Frontend | 0.5d |
| 6.16 | Spasibo BU write-back design memo (R#29) | SA | 1.5d |

**Total code: ~31 person-days vs 30 capacity — healthy.**

### Non-code work

| # | Task | Owner | Deadline |
|---|---|---|---|
| 6.A | First MM contracts signed (2-3 anchor) — prep for Sprint 7 #6.3-6.5 | PO + Legal | end of sprint |
| 6.B | OTC client onboarding (first 3 institutional) — prep for Sprint 7 #6.1-6.2 | PO + Corp Sales | end of sprint |
| 6.C | Атомайз / Мастерчейн listing discovery memo | SA | sprint mid |
| 6.D | Минцифры реестр phase-1 form prep | PO + Legal | end of sprint |
| 6.E | SBBOL §7 q3 chase — escalate to Sber integrations leadership by Day 3 | PO | Day 3 |

---

## Distribution (Day-1 standup)

- **Backend dev 1 (lead)** → **3.1 → 3.2 (Day 1-2)** → 6.9 AML scheduler → support 5.13
- **Backend dev 2** → 6.7 самозапрет backend → 5.13 SBBOL OIDC (when q3 lands)
- **Backend dev 3** → 6.8 ЕСИА OIDC → support 5.13
- **Frontend** → 5.6-FE B2B portal (Day 1-4) → 6.7 самозапрет toggle → 6.14 mass-action → 6.15 deep-link
- **SRE** → 5.F k6 run (Day 1) → 6.10 Pangolin CI matrix
- **SA** → 6.C Атомайз memo → 6.16 Spasibo write-back memo
- **PO** → 6.A, 6.B, 6.D, 6.E daily

---

## Day-1 critical path (DO THESE BEFORE ANYTHING ELSE)

1. **Backend dev 1 → start 3.1 immediately.** This is the highest-leverage
   day-1 work — 1 day of code unblocks ~6 months of revenue accumulation
   that's been waiting on 3.A legal memo. **Already in progress as of this
   doc's writing.**
2. **PO → submit §7 q3 escalation letter** by EOD Day 1. If response
   doesn't land by Day 3, 5.13 cleanly shifts to Sprint 7 (Plan-B prepared).
3. **Frontend → receive D-01 Figma mockups from designer** Day 1
   morning. Without these, 5.6-FE estimate explodes.
4. **SRE → k6 staging run** Day 1 evening. Numbers attach to acceptance
   gate before sprint mid.
5. **SA → 6.C Атомайз memo draft.** Solo work, independent of everything.
   Verdict by sprint mid gates the M-29 commercial decision in Sprint 7
   planning.

---

## Sprint 6 acceptance criteria (PO close-meeting checklist)

Day-1 unblocks:
- [ ] `protocol_fee_pct=5%` live on at least 1 test pool, end-to-end verified
- [ ] k6 single-pool re-baseline numbers attached to commit, swap_errors < 5%
- [ ] SBBOL OIDC sandbox silent SSO works (or Plan-B documented if §7 q3 slipped)
- [ ] B2B portal frontend: issuer self-service registration + admin KYB review live

RU compliance core:
- [ ] Самозапрет toggle blocks swap end-to-end (test: enable → 403 USER_SELF_RESTRICTED)
- [ ] ЕСИА login silent SSO from Госуслуги test environment
- [ ] AML pattern alert fires on synthetic split-amount sequence
- [ ] CI matrix green on Postgres 16 + Pangolin

Cross-functional:
- [ ] 6.C Атомайз memo accepted with Q4 go/no-go verdict
- [ ] 6.D Минцифры phase 1 form submitted
- [ ] 2-3 MM contract drafts in legal review (prep for Sprint 7)
- [ ] 3 OTC institutional clients in onboarding pipeline (prep for Sprint 7)

---

## Risks for this sprint

| # | Risk | Mitigation |
|---|---|---|
| R-new-1 (carry) | SBBOL §7 q3 slipping further | Plan-B: 5.13 to Sprint 7 day 1. PO escalation 6.E by Day 3. |
| R-new-2 | 6.8 ЕСИА depends on 5.13's `dlmm-common/auth/oidc` shared bus landing first | Backend dev 2 swap order: 5.13 first, 6.8 second |
| R#28 (Sprint 5) | k6 retry-loop tail latency at 5×attempts | If staging run violates threshold → tune `MAX_SWAP_ATTEMPTS` or per-bin locking (Option B) |
| R-new-32 | No permanent designer assigned (UX-001 in `UX-REVIEW`) | Sprint 7+ budget request to PO; designer is one-time review only |
| R#30 (carry) | B2B portal frontend sales pre-empted | Mockups D-01 by Day 1 critical path #3 |
| R#13 (long) | PagerDuty still not wired (4.D) | PO escalates rotation nomination Day 1 standup |

---

## What's parked, not in this sprint

Confirmed not building Sprint 6:
- **OTC desk + MM rebate** (moved to Sprint 7)
- **API access paid tiers 6.6** (moved to Sprint 7, bundled with M-33 Public Data API)
- **Revenue research T1 picks** (d, h, m, M-24, M-30, M-33) — Sprint 7 absorb
- **UX critical fixes 7-item block** — Sprint 7 UX track
- **Sprint 8+ revenue picks** (p, k, b, e, n, M-22, M-31, M-36) — Sprint 8 backlog

---

## Companion artefacts produced today

| Document | Purpose |
|---|---|
| `docs/SPRINT-PLAN.md` (updated) | Sprint 6/7 rebalance reflected |
| `docs/REVENUE-RESEARCH-2026-06-03.md` | Sprint 7-8 commercial intake (38 monetization ideas scored) |
| `docs/UX-REVIEW-2026-06-03.md` | 47 UX findings + Sprint 7-9 UX backlog |
| `docs/SPRINT-6-KICKOFF.md` (this file) | Sprint 6 daily plan |

---

*Recorded by: IT-lead + SA + Designer (in role). Sprint 6 acceptance ceremony: 2026-06-16.*
