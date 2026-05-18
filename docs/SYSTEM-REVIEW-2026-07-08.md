# System Review — 2026-07-08 (post-Sprint-8)

**Trigger**: Sprint 8 close — "UX Hardening Sprint" delivered all 9 of 9
hard gates. Time to step back and measure where the platform actually is
3 weeks after the system audit (`SYSTEM-AUDIT-2026-06-17.md`) called
for the rebalance.

**Format**: short. The deep 508-line audit doesn't need a sequel; this
is the *acceptance test* for the audit's hypothesis ("if we do the UX
Hardening Sprint, the asymmetry closes").

---

## 1. Audit hypothesis: was it right?

The audit (commit `331b225`) made a single bet: **invest 1 sprint
purely in frontend / safety-rail / security work, and the platform's
"production-launch-ready by Sprint 10" forecast holds.**

Measured outcome after Sprint 8 (commits `f4445d7` through `73163ef`,
18 commits):

| Audit prediction | Sprint 8 result | Verdict |
|---|---|---|
| Backend 9/10 | Backend 9/10 (no regression) | ✅ |
| Frontend 6/10 → 8/10 | Frontend ~8/10 (tests + hex sweep + a11y + mobile + i18n) | ✅ |
| Ops 7/10 → 8/10 | Ops ~8/10 (CB extended, audit log, JWT revoke) | ✅ |
| Process 6/10 | Process 7/10 (27d ceiling held — Sprint 8 shipped 28d) | ✅+ |
| Overall 7.5/10 → 8.5/10 | Overall ≈ 8.7/10 | ✅+ |
| Production-launch-ready by Sprint 10 | Now: Sprint 9 if commercial backlog stays disciplined | **Accelerated** |

The bet paid off. **The audit recommendation was correct.**

---

## 2. Where the new bottleneck is

Quality is no longer the gate. **Commercial throughput is.**

Three sprints (6, 7, 8) of capacity went into:
- Sprint 6: hedge mass-action + AML + Pangolin + Spasibo design
- Sprint 7: SBBOL stub + MM rebate launch + B2B portal FE + UX critical
- Sprint 8: 100% UX hardening (no commercial code shipped)

Net new revenue surface added in those 3 sprints: ~10M ₽/yr (MM rebate
launch + B2B integration fee). The cumulative-revenue table in
`SPRINT-PLAN.md` slipped Q1 2027 from 1.5-2B to 1.3-1.8B ⤓ as a
consequence — a 150-200M ₽/yr shortfall.

**The trade was deliberate** (audit AU-1 decision) and correct. But
Sprint 9 must aggressively ship commercial features or the 1.3-1.8B
floor risks becoming 1.0-1.3B.

---

## 3. Top 5 platform-quality issues remaining

In priority order (severity × cost-to-fix):

### 3.1 Admin-bff still has 5 inline WebClients (R#33)
- **Risk**: Same blow-up-cascade issue C-10 closed for 3 services.
- **Fix**: 1d per client × 5 = 1 sprint of incremental work.
- **Action**: Sprint 9 Day-5 SRE slot if capacity allows; otherwise Sprint 10.

### 3.2 Cross-app component duplication (R#39, audit M-1)
- **Risk**: StatCard, formatRub, color palettes — 2 copies that drift.
- **Fix**: npm workspaces + `dlmm-ui-common` package (3-4d).
- **Action**: Sprint 10 — needs a longer concentration block than Sprint 9 will have.

### 3.3 BinLiquidityChart hex literals (41 across both UIs)
- **Risk**: Visual-token drift via recharts API. Doesn't block anything.
- **Fix**: Chart-color palette module pattern (1d).
- **Action**: Sprint 9 filler slot (last 2h if hard items finish early).

### 3.4 No EN translation (C-4 part 2)
- **Risk**: International / corporate-English users blocked.
- **Fix**: Marketing copy-edit pass on `en.json` mirror of `ru.json` (3-5d, mostly marketing labour).
- **Action**: Sprint 10 once Sprint 9 finishes the 4 remaining-page extraction.

### 3.5 Visual regression for mobile breakpoints (UX-MOBILE-1 follow-up)
- **Risk**: Sprint 8 mobile sweep tested via Chrome devtools only. Future CSS edits could silently break 320/375 layouts.
- **Fix**: Playwright snapshot test per page × 3 viewports (1-2d).
- **Action**: Sprint 9 or 10 — small but valuable.

---

## 4. Surprises

### 4.1 Test discipline normalised faster than expected
Admin-UI went from 0 → 17 tests in 3 hours (Sprint 8 C-7). Sprint 9
should target 30-40 admin-ui tests covering UsersPage, PoolsPage,
TokensPage forms. The pattern is now obvious and repeatable.

### 4.2 Hex ratchet is changing PR culture, not just enforcing rules
Two sweeps (243 → 165, 32% cut) happened because the ratchet made
"how many hex are we at?" a question that gets asked. Same effect
expected for a11y once we add a similar `aria-count` ratchet
(Sprint 10 candidate).

### 4.3 R#46 quality asymmetry closed cleaner than predicted
Audit forecast was score 20 → 9 after 1 sprint. Actual: 20 → ~8.
The designer engagement (R#29) didn't slip the sprint as feared —
the FE devs produced enough design-tokens discipline themselves
through the hex ratchet that the designer arrival isn't on the
critical path any more. **Promoting AU-1 to "model decision"**:
when quality lags but is *uniformly* poor (not blocked on one
specialist), an entire sprint of investment cleans it faster than
ongoing trickle-fixes.

---

## 5. Recommendation for Sprint 9 + beyond

1. **Sprint 9 = commercial sprint, hard discipline**. Anchors:
   OTC desk (6.1+6.2), Money market (7.1+7.2), Spasibo write-back
   (if 8.C contract lands). Carry from Sprint 8 ≤ 5d total.

2. **Designer onboarding** (R#29) — close 8.D this sprint. Without
   permanent designer, sprint 11+ visual-design work stalls.

3. **Demo cadence**: hold demos at every sprint close from now on
   (was every 2 sprints). Sprint 9 close should show OTC first trade
   + money market first yield credit + design progression (Dashboard
   before/after sweep).

4. **Sprint 10 plan**: index funds + admin-bff WebClient finish +
   workspaces refactor + EN translation. Tighter than Sprint 9.

5. **Q3 2026 commercial target**: 700M-1.0B ₽/yr run-rate by Sprint 9
   close (per Sprint 8 cumulative-revenue table). To hit this, Sprint 9
   must ship the OTC + money market launches; both have signed
   commitments (8.B legal memo, 8.A pipeline reinforcement) ripening
   from Sprint 8.

---

## 6. What the platform actually looks like now

- **Backend** — 7 microservices + gateway + 1 BFF. 270+ tests. JWT
  revoke + admin audit log + Resilience4j on 4 services + transactional
  outbox on 2. Pure-static decision functions across detectors.
  Hikari + Prometheus + Grafana monitored. PagerDuty wired.

- **Frontend** — 2 React apps. 85 tests (68 user + 17 admin). Playwright
  e2e harness on user-ui. Design-tokens enforced via hex ratchet
  (243 → 165, target ≤ 120 Sprint 9). 35 aria attributes. Mobile
  @media at 320/375/414/768. i18n foundation + RU resource (1 page
  fully extracted, 4 ready).

- **CI/CD** — GitHub Actions matrixed across both UIs. Hex ratchet
  gates every PR. Playwright job downstream of build matrix. Pangolin
  advisory leg surveillance. Container-scan weekly.

- **Compliance** — Lead in daily standup. 5.D ЦФА memo, 3.A protocol
  fee memo, 3.F B2B reg-cat memo, 6.9 AML detectors, 6.7 самозапрет.
  152-ФЗ + 115-ФЗ + 161-ФЗ Track 3 cascade pending (Sprint 10+).

- **Commercial** — protocol fee + exit + custody + sponsored pools live
  since Sprint 3. FX hedges + B2B settlement live since Sprint 4.
  SberSpasibo + MM rebate live since Sprint 7. Treasury LP active.
  3 OTC pilots in late-stage pipeline. **Run-rate today: ~600M ₽/yr.**

- **Tech debt** — 5 inline WebClients in admin-bff, BinLiquidityChart
  hex, cross-app component dup. None blocking.

**Banking-grade for retail + B2B pilot**. Ready for Sprint 9 commercial
push. Not yet ready for госконтракт / реестр (Track 2 needs index +
money market + 152-ФЗ — Sprint 10-11).

---

*Recorded by: IT-lead. Sprint 8 retro draft companion. Sprint 9 kickoff
follows.*
