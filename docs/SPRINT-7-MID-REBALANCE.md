# Sprint 7 — Mid-sprint rebalance (2026-06-23)

**Status**: Sprint 7 day ~6 of 10. Three commits of emergent
audit-follow-up work landed (see "Absorbed" below). This memo
makes the trade explicit instead of pretending the kickoff
budget still holds.

> **Companion to** `docs/SPRINT-7-KICKOFF.md` (the budget this
> memo amends) + `docs/CODE-REVIEW-2026-05-18.md` (the work
> this memo accounts for).

---

## 1. Absorbed (not on Sprint 7 kickoff)

Three commits since kickoff that **weren't on the backlog** but
addressed audit findings that surfaced post-kickoff:

| Commit | What | Audit ref | Effort |
|---|---|---|---|
| `1e318e1` | Dedup: TokenChip + admin StatCard extracted to shared components | C-9 partial, M-1 partial | ~1d |
| `8dfa63c` | Hardened protocol-fee tests (bounds → exact arithmetic + additive case) | self-flagged in code review | ~0.5d |
| `ce8a601` | Playwright e2e harness: login (2 cases) + swap (2 cases) + CI job | C-9 partial — first FE e2e ever | ~2d |

**Total absorbed: ~3.5 person-days of frontend / test engineering capacity.**

---

## 2. Cut to make budget honest

Sprint 7 kickoff already at +17% over (35d vs 30d capacity). Adding
3.5d absorbed without cuts → +29% over → guaranteed slip. **Cuts:**

| # | Item | Effort | Moved to | Reason for cut |
|---|---|---|---|---|
| UX-042 | SwapPage mobile breakpoint fix | 1d | Sprint 8 | Bundles cleanly with Sprint 8 mobile breakpoint sweep (audit C-2) — doing it standalone wastes setup |
| UX-016 | Hedge unwind quote language fix | 0.5d | Sprint 8 | Small enough to bundle with Sprint 8 UX-Major block |
| R-Pangolin-1 | Pangolin BIGINT InvariantTest divergence | 0.5d | Sprint 8 | SRE capacity is the bottleneck; investigation can wait one sprint |
| R-UX-035 | SelfRestrictionPanel "Cancel set" undo | 3h | Sprint 8 | Designer engagement deferred to Sprint 7C → no design context for undo UX |

**Total cut: ~2.5d.** Net revised over: +1d, well within stretch tolerance.

**Items kept** (still on Sprint 7 commit):
- 5.13 (SBBOL OIDC stub) — gate item, can't slip
- 5.6-FE (B2B portal frontend) — depends on D-01 already in hand
- 6.8 (ЕСИА OIDC) — Sprint 6 carry-over
- 6.3 / 6.4 / 6.5 (MM rebate scheduler + tier + onboarding) — Sprint 6 carry, MM contract on critical path
- R-d (B2B integration fee), R-m (NDS badge) — small revenue T1 picks
- UX-003 (Tx drill-down), UX-007 (slippage chip), UX-037 (a11y price-impact), UX-008 (insufficient-balance error), R-UX-036 (positions ?highlight=) — Critical UX block remainders

---

## 3. Why the absorbed work was worth doing mid-sprint

System audit (commit `331b225`) was the first measured top-to-bottom
review and surfaced findings the team **could not safely ignore until
Sprint 8**:

- **C-9** "FE test coverage gap on most-complex pages, untested" —
  Playwright harness ce8a601 is the foundation for any future FE
  test work. Doing it after Sprint 8's UX hardening would mean
  shipping more untested UI churn first.
- **Dedup commits** removed a confession-commented copy ("Same
  accent function as PoolsPage — keeps token chips consistent")
  that was actively confusing the new joiner expected for Sprint 9.
- **Protocol-fee test hardening** — found my own Sprint 6 #3.2
  test using `assertTrue(>=0, <=gross)` bounds that would silently
  pass if the protocol slice was 0. Self-flagged during the audit
  pass. Fixing immediately prevents a Sprint 7 retro item.

These are **safety-rail investments** — the kind of work that
doesn't fit any sprint's commercial backlog but compounds across
quarters. Sprint 6 retro AI-10 (the "tech-debt buffer" suggestion)
applies — except we ate it from the live sprint instead of from
explicit buffer. Sprint 8 kickoff should formalise a buffer slot.

---

## 4. Updated Sprint 7 acceptance criteria

Remove from kickoff §7:
- ~~UX-016 hedge unwind quote language~~ → Sprint 8
- ~~UX-042 SwapPage mobile breakpoint~~ → Sprint 8
- ~~R-Pangolin-1 investigation~~ → Sprint 8
- ~~R-UX-035 undo button~~ → Sprint 8

Add new (the absorbed work):
- [x] Cross-page dedup landed (TokenChip + StatCard, 9 + 8 tests)
- [x] Protocol-fee assertions hardened (21/21 SwapServiceTest green)
- [x] Playwright e2e harness live (4 cases, CI job, ~135MB chromium cached)

Everything else from kickoff §7 still holds.

---

## 5. Velocity reality-check (preview for Sprint 7 retro AI-3)

Original SPRINT-PLAN was built assuming 30 person-days. Two
consecutive rebalances + this mid-sprint amendment confirm the
team is running closer to **25-27 realised days per sprint** when
audit/safety-rail work is counted (which it should be).

**Sprint 8 kickoff must use 27d, not 30d, as planning ceiling.**
Pre-baked into the new Sprint 8 plan (see `SPRINT-8-KICKOFF.md`).

---

*Recorded by: IT-lead. Sign-off: PO (verbal day-6 standup).*
*Companion to SPRINT-7-KICKOFF.md (kept as-is for historical record).*
