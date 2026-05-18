# Sprint 9 — Kickoff

**Date**: 2026-07-16 (day after Sprint 8 acceptance).
**Window**: 2 weeks.
**Theme**: **"Commercial expansion — OTC desk + Money market + Spasibo write-back"**
**Capacity ceiling**: **27 person-days** (Sprint 8 retro confirmed the
ceiling — Sprint 8 shipped 28d realised, within tolerance).

> **Context**: Sprint 8 = "UX Hardening Sprint" closed all 9 of 9 hard
> gates. Audit hypothesis confirmed (`SYSTEM-REVIEW-2026-07-08.md`).
> Commercial throughput is now the bottleneck per
> `SPRINT-PLAN.md` cumulative-revenue table — Q1 2027 forecast dropped
> 1.5-2B → 1.3-1.8B because of the UX Hardening trade. **Sprint 9 must
> ship the deferred commercial backlog to recover the path.**

---

## 1. Source documents

| Doc | Purpose |
|---|---|
| `docs/SPRINT-8-REVIEW.md` | Sprint 8 metrics + slip risk |
| `docs/SYSTEM-REVIEW-2026-07-08.md` | Post-Sprint-8 system state |
| `docs/NEW-FEATURES-BACKLOG-2026-07-08.md` | New feature menu (Sprint 10+) |
| `docs/SPRINT-PLAN.md` Sprint 9 section | Commercial backlog originally slid from Sprint 8 |
| `docs/MONETIZATION-STRATEGY.md` §M#7 OTC, §M#9 Money market | Source-of-truth for both anchor commercials |
| `docs/SPASIBO-WRITEBACK-DESIGN.md` | #6.16 design (impl gated on 8.C contract) |

---

## 2. Sprint goals (PO callout)

1. **OTC desk LIVE** — first ≥ 10M ₽ block trade settled via the new
   admin workflow + RFQ API. Counter for the 600M ₽/yr → 800M ₽/yr
   Q3 run-rate jump.
2. **Tokenized money market (YSRUB) LIVE** — first mint, first daily
   yield credit, first burn. Adds a new asset class to the platform.
3. **Spasibo write-back LIVE** (conditional on 8.C contract close) —
   first real cashback credit from a partner merchant.
4. **API access paid tiers live at gateway** — Free / Pro /
   Enterprise with rate-limit tiers. Sets up Sprint 10 F-15 analytics.
5. **Sprint 8 carry items closed**: 4 UX-Major (M-2/3/4/5),
   admin-bff WebClient finish, EN translation start, i18n other 4
   pages.

If 1+2+4 land hard, Sprint 9 succeeded. 3 is conditional. 5 is
hygiene track.

---

## 3. Backlog (post-discipline cut)

### 3.1 Commercial anchors (~19d)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| **6.1** | OTC desk admin workflow — block-trade entry, settlement, audit trail (wires `@AdminAudit` per AU-4) | M#7 (Sprint 6→7→8→9 carry) | Backend lead + Admin UI | 6d |
| **6.2** | RFQ API for VIP clients — `POST /api/v1/otc/rfq`, multi-LP quote aggregation, expiry timer | M#7 | Backend dev 2 | 4d |
| **7.1** | Tokenized money market (YSRUB) — `mint` / `burn` endpoints, daily yield outbox event | M#9 | Backend dev 3 | 8d |
| **7.2** | Yield distribution engine — overnight rate + spread credit to YSRUB holders | M#9 | Backend dev 3 | 5d (overlaps 7.1) |

**Note on 7.1+7.2**: Backend dev 3 plays them serially — 7.1 first
4d, then 7.2 4d, then 7.1 polish 4d. Pairs naturally; same person
keeps full context.

### 3.2 API tier infrastructure (~6d)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| **6.6** | API access paid tiers at gateway — Redis-backed rate limiter per API key tier (Free 10rps / Pro 100rps / Enterprise unlimited) | M#20 | SRE + Backend | 3d |
| **R-M-33** | Public Data API tiers (extension of 6.6) — read-only endpoints for analysts | Revenue research | Backend | 3d |

### 3.3 Spasibo write-back (~8d, CONDITIONAL on Sprint 8 8.C contract)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| **6.16-impl** | Spasibo write-back implementation per design memo | #6.16, 8.C gate | Backend | 8d |

**Plan-B if 8.C slips**: replace this block with **F-13 SLA MM
contract paper** (PO + Legal cross-functional, no code) + **F-21
Self-service KYC re-verification** (3d code). Decision at Day 3
standup once 8.C status known.

### 3.4 Sprint 8 carry-overs (~6d)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| **M-2** | LoginPage "Forgot password?" + stub reset flow | Audit M-2 | BE + FE | 1.5d |
| **M-3** | RegisterPage 2-step wizard | Audit M-3 / UX-027 | FE | 1.5d |
| **M-4** | Dashboard stat tiles → drill-down (Link per tile) | Audit M-4 / UX-001 | FE | 1d |
| **M-5** | PoolsPage filter + sort | Audit M-5 | FE | 1d |
| **C-10-bff** | admin-bff: extract 2 of remaining 5 WebClients (user-service, transaction-service — most-trafficked) | R#33 | Backend dev 3 last 2d | 1d |

### 3.5 i18n + a11y wave 2 (~3d)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| **C-4-rest** | Extract other 4 pages (Dashboard / Hedge / Pools / Login) to t() calls — keys already in ru.json | Sprint 8 C-4 carry | FE | 1d |
| **UX-A11Y-2** | wave 2: skip-to-content link + aria-current="page" on Sider + Sider tree role attrs | Audit C-1 | FE | 2d |

**Total Sprint 9 code: ~42d if Spasibo lands** vs 27d ceiling = +56% over.
**Without Spasibo: ~34d** = +26% over.

**Mandatory cuts**:
- M-2 + M-3 → drop to Sprint 10 (lower priority than commercials).
  Saves 3d.
- C-4-rest deferred to filler last 2h Day 10 if time. Saves 1d.
- After cuts: ~30d with Spasibo, ~22d without. **Doable.**

### 3.6 Non-code

| # | Task | Source | Owner | Deadline |
|---|---|---|---|---|
| 9.A | First OTC block trade signed + executed (≥ 10M ₽) | PO + Corp Sales | Day 8 |
| 9.B | YSRUB legal frame (РепоТипо) finalized (carry from 8.B) | Compliance + Legal | Day 5 |
| 9.C | First Spasibo cashback credit (gated on 8.C contract) | PO + Loyalty BU | Day 10 (conditional) |
| 9.D | Sprint 10 plan draft — F-13 SLA MM contracts + F-25 SberID outline | PO + IT-lead | Sprint mid |
| 9.E | OTC pipeline reinforce to 5 prospects (was 3/3 by Sprint 8 close) | PO + Corp Sales | End of sprint |

---

## 4. Day-1 critical path

1. **Backend lead → start 6.1 OTC admin workflow**. Longest single
   item; needs Day-1 momentum. Wire `@AdminAudit` from AU-4 right
   from the start so audit trail is built-in.
2. **Backend dev 3 → start 7.1 YSRUB mint/burn**. Same reason; 7.1+7.2
   is 13d serial.
3. **Backend dev 2 → 6.2 RFQ API design notes → start coding**. Day 2-5
   builds the multi-LP aggregator.
4. **Frontend → M-4 Dashboard drill-down first (1d, smallest)**, then
   M-5 PoolsPage filter+sort. After 2d, jump onto admin UI work for
   6.1 (admin workflow needs an admin page).
5. **SRE → 6.6 + R-M-33 in parallel** (both gateway-side, 3+3d
   compatible).
6. **PO → 9.A OTC trade execution prep + 9.E pipeline outreach + 9.C
   Spasibo status check by Day 3**.

---

## 5. Sprint 9 acceptance criteria

**Hard gates** (must ship):
- [ ] 1 OTC block trade settled, ≥ 10M ₽ notional, visible in admin audit log
- [ ] YSRUB mint endpoint live; first user holds non-zero balance
- [ ] YSRUB burn endpoint live; round-trip mint→burn works
- [ ] Daily yield outbox event published; first scheduled credit fires
- [ ] API tiers live at gateway: Free key rate-limited to 10rps, Pro 100rps (k6 verified)
- [ ] Public Data API tiers (R-M-33) live: `/api/v1/public/pools/stats` reachable
- [ ] Dashboard tile drill-downs work (each tile → filtered list page)
- [ ] PoolsPage filter+sort UI live
- [ ] admin-bff: 3 of 6 WebClients now circuit-broken (Sprint 8 1/6 → Sprint 9 3/6)
- [ ] UX-A11Y-2 wave 2: skip-to-content link + aria-current on Sider in both UIs

**Stretch** (cut first):
- [ ] Spasibo write-back: first real cashback credited (conditional on 8.C)
- [ ] M-2 LoginPage "Forgot password?" stub
- [ ] M-3 RegisterPage 2-step wizard
- [ ] C-4-rest: 4 more pages translated to t()

---

## 6. Risks for this sprint

| # | Risk | Mitigation |
|---|---|---|
| **R-S9-1** | OTC pipeline reinforcement (9.E) stalls — Sprint 8 ended at 3/3 prospects, market headwinds | Plan-B: ship 6.1+6.2 against 1 anchor client only; revenue lands Sprint 10 |
| **R-S9-2** | YSRUB legal frame (9.B) not signed off by Day 5 — slips 7.1 | 7.1 code lands behind a feature flag; soft-launch internal-only |
| **R-S9-3** | 8.C Spasibo contract not closed by Day 1 → 6.16 work doesn't happen | Plan-B in §3.3 documented; 8d frees for F-13 SLA paper + F-21 KYC re-verify |
| **R-S9-4** | Capacity over by +26% without Spasibo, +56% with | M-2+M-3 already cut; M-4+M-5 cuttable if velocity slips Day 5 |
| **R-S9-5** | API tier rate limiter conflicts with existing Spring Cloud Gateway request-rate-limiter (Sprint 1) | SRE Day-1 design check — should be config layer above existing limiter, not a replacement |
| **R-S9-6** | Designer engagement (R#29) still soft-committed Sprint 8 8.D | Sprint 8 hex sweeps proved FE devs can carry without designer; not blocking |

---

## 7. What's parked, NOT in Sprint 9

Confirmed not building:

- **Index funds + Spasibo write-back DESIGN** (7.3-7.5) — Sprint 10
  (already slid per AU-1)
- **All Sprint 10 new features** (F-01, F-13, F-25, etc. from
  NEW-FEATURES-BACKLOG-2026-07-08.md)
- **Tokenized bonds, derivatives, vault strategies, NFT auctions** —
  parking lot
- **EN translation** — Sprint 10+ once C-4-rest ships in this sprint
- **Workspaces refactor** (R#39 cross-app dup) — Sprint 10
- **BinLiquidityChart hex sweep** — Sprint 9 filler slot only if time
- **152-ФЗ / 115-ФЗ Track 3** — Sprint 10-11 cascade
- **Distributed tracing (Sleuth + Zipkin)** — Sprint 10 per AU-5
- **Maven dependency-check** — Sprint 10 per AU-6

---

## 8. Capacity reality-check (cumulative)

| Sprint | Planned | Realized | Δ | Lesson |
|---|---|---|---|---|
| Sprint 5 | 32d | ~28d | −13% | Compliance sign-offs faster than expected |
| Sprint 6 | 30d | 35d | +17% | Rebalance #1 (OTC/MM moved out) |
| Sprint 7 | 35d | 32d | −9% | Mid-rebalance absorbed 3.5d audit emergent |
| Sprint 8 | 28d | 28d | 0% | First sprint to hit planned exactly |
| **Sprint 9 plan** | **27d** | **TBD** | **TBD** | Discipline test |

Trend: realised velocity stabilising around **27-32d**. Sprint 9
ceiling of 27d is the conservative floor — if hit, the stretch items
either land or move cleanly to Sprint 10 without panic.

---

## 9. Demo plan

Sprint 9 demo: **2026-07-29 (Day 10, same day as acceptance)**.
Audience: PO + IT-lead + Compliance + Corp Sales + Marketing.
Full plan: `docs/SPRINT-9-DEMO-PLAN.md`.

Headline narrative: **"from quality foundation to commercial scale"**:
1. Sprint 8 quality wins (hex 243 → ~120, aria 4 → 50+, mobile @media live)
2. OTC desk first live trade (corporate flagship)
3. YSRUB mint/yield/burn end-to-end (new asset class)
4. API tiers gateway demo (developer ecosystem)
5. Spasibo write-back (if 9.C lands)

---

## 10. Companion artefacts

| Document | Purpose |
|---|---|
| `docs/SPRINT-PLAN.md` (will be updated this kickoff) | Sprint 9 commercial backlog |
| `docs/SPRINT-8-REVIEW.md` | Sprint 8 metrics + carry context |
| `docs/SYSTEM-REVIEW-2026-07-08.md` | Audit hypothesis confirmation + new bottleneck |
| `docs/NEW-FEATURES-BACKLOG-2026-07-08.md` | Sprint 10+ menu |
| `docs/SPRINT-9-DEMO-PLAN.md` | Demo runbook |

---

## 11. Retro question to track (carry from Sprint 8 retro)

**"Does the 27d capacity ceiling hold once commercial sprints
restart?"** Sprint 8 was hygiene-heavy (predictable); Sprint 9 is
commercial-heavy (integration risk, external partners). If Sprint 9
ships ≤ 24d realised, recalibrate ceiling for Sprint 10.

---

*Recorded by: IT-lead + Backend lead + SA + PO. Sprint 9 acceptance
ceremony: 2026-07-29. Sprint 10 kickoff: 2026-07-30.*
