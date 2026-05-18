# Sprint 9 — Demo Plan

**When**: 2026-07-29, **15:00 МСК**, 60 minutes (40 demo + 20 Q&A).
**Where**: Hybrid — Sber HQ Бутырский 1.6 (in-person) + Zoom for remote.
**Who's presenting**: IT-lead (driver), PO (narrative), Compliance lead
(reg-frame answers), Backend lead (technical deep-dive on Q&A).

## 1. Audience + tailored hook

| Audience | Hook |
|---|---|
| **PO Council** (3 PO peers) | Commercial throughput recovery — OTC live, run-rate path to 800M-1.0B ₽/yr by Q3 close |
| **Compliance lead** | New AdminAudit AOP + JWT revocation in production; 152-ФЗ delete-me path designed |
| **Corp Sales lead** | OTC first trade walkthrough — sales narrative for next 5 prospects |
| **Marketing lead** | YSRUB launch — first retail-side new asset class since SBER10 plan; brand opportunity |
| **Sber Treasury rep** | API tiers + MM rebate analytics → larger LP placement justified |
| **IT-lead Council** (peer reviewers) | Sprint 8 quality wins + Sprint 9 commercial — replicable model |
| **Sber Online BU rep** (new this demo) | Sprint 10 F-25 SberID integration outline — soft-pitch their participation |

**Critical**: this is the **first demo with Sber Online BU
representation**. The 30-minute slot at the end is the "pitch SberID
integration" moment.

## 2. Agenda (40 min demo + 20 min Q&A)

```
00:00 – 03:00   Welcome + Sprint 8 quality recap (1 slide, 3 metrics)
03:00 – 08:00   OTC desk first trade (LIVE in admin UI)
08:00 – 14:00   YSRUB mint → daily yield → burn (LIVE in user UI)
14:00 – 19:00   API tiers gateway demo (Postman + Grafana)
19:00 – 23:00   Spasibo write-back demo (LIVE if 9.C, otherwise design walkthrough)
23:00 – 28:00   Sprint 8 quality progression (Dashboard before/after, hex 243 → ?, aria, mobile)
28:00 – 33:00   Risk-register diff + Sprint 9 acceptance scorecard
33:00 – 38:00   Sprint 10 preview — F-25 SberID SSO outline (Sber Online BU pitch)
38:00 – 40:00   Sprint 9 retro top-3 + next-sprint commitment
40:00 – 60:00   Q&A (open mic + Slido)
```

## 3. Per-segment runbook

### 3.1 OTC desk first trade (03:00–08:00, 5 min)

**Driver**: IT-lead, with Corp Sales standing by to introduce the
counter-party narrative.

**Live actions**:
1. Open admin-ui `/otc/desk` (new page, Sprint 9 #6.1)
2. Click "Create RFQ" → enter test counter-party + 15M ₽ SRUB→USDT
3. Show pulled quotes from 3 LPs (test environment — pre-seeded)
4. Select best quote → execute
5. Switch to admin audit log (`/admin/audit?targetType=POOL&targetId=...`)
   to show the full trail: who clicked, when, what was the quote, what
   was the executed price (audit C-6 + AU-4 in action)

**Talking points**:
- "Every admin action since Sprint 8 lands here — auditable by design."
- "Today's first real-money trade with Pilot Client X is scheduled for
  Day-after-demo; pipeline at 5 prospects (was 3 at Sprint 8 close)."

**Failure mode + fallback**:
- If OTC code didn't fully ship → show the Sprint 9 acceptance test
  recording (pre-recorded Day 9 as belt-and-braces).
- If RFQ aggregator throws (network) → use the offline `scripts/
  demo-otc-trade.ps1` fallback (Sprint 9 will ship this scripted
  end-to-end demo same as Sprint 4 #3.D pattern).

### 3.2 YSRUB end-to-end (08:00–14:00, 6 min)

**Driver**: Backend lead.

**Live actions**:
1. As a verified user — open `/yield` (new page if Sprint 9 ships FE,
   else use admin proxy)
2. Mint 100,000 YSRUB by depositing 100,000 SRUB
3. Show `/api/v1/tokens/balances/me` includes YSRUB now
4. Manually trigger the daily yield scheduler via admin endpoint
   `/admin/yield/trigger` (test-only)
5. Show user balance went up by ~16% / 365 = 0.04% (overnight + spread
   credited)
6. Burn 50,000 YSRUB → get SRUB back
7. Round-trip ledger consistency demonstrated

**Talking points**:
- "New asset class on the platform — first since SBER10 design."
- "1:1 backed by Sber overnight deposits (legal frame 9.B finalized
  this sprint)."
- "Daily yield is real Sber rate + 30bps spread to LPs."

**Failure mode + fallback**:
- If yield scheduler isn't firing → manually invoke the underlying
  service method via Swagger; explain it's a cron-based schedule
  that fires 04:00 МСК daily.
- If FE page slipped → use admin Swagger directly for mint/burn,
  pre-recorded user-side FE flow as backup video.

### 3.3 API tiers gateway demo (14:00–19:00, 5 min)

**Driver**: SRE.

**Live actions**:
1. Open Postman with 3 API keys (Free / Pro / Enterprise)
2. Fire 30 rapid requests through Free key → show 429 after 10 (10rps
   limit)
3. Same script with Pro key → succeeds (100rps headroom)
4. Show Grafana panel "API tier usage" — per-key call counts
5. Open Public Data API spec (`/api/v1/public/pools/stats`) — read-only
   no auth, shows JSON of all-pool TVL/volume aggregates

**Talking points**:
- "Free tier seeds the developer ecosystem (Sprint 10 F-26 SDK plays
  on this)."
- "Pro tier $X/mo, Enterprise $Y/mo — direct M#20 revenue."
- "Public Data API is the foundation for F-27 market-data feed
  (analysts → adoption multiplier)."

**Failure mode**: if Grafana panel isn't auto-provisioned, just show
the Prometheus query directly.

### 3.4 Spasibo write-back (19:00–23:00, 4 min)

**Driver**: PO (with Loyalty BU lead in the audience).

**If 9.C contract landed + 6.16-impl shipped**:
- Demo flow: simulate purchase on partner merchant → Kafka event
  arrives → Spasibo balance credited → user sees notification +
  refreshed balance in `/profile`

**If 9.C didn't land**:
- Walk through `SPASIBO-WRITEBACK-DESIGN.md` slide deck
- Show the existing SSPAS mint/burn (Sprint 5 #5.1-5.3) so the
  "platform-ready, waiting on contract" narrative is concrete
- Sprint 10 commitment from Loyalty BU rep on contract close

### 3.5 Sprint 8 quality progression (23:00–28:00, 5 min)

**Driver**: IT-lead.

Slide deck (5 slides):
1. **Dashboard before/after** — split screen, Sprint 7 vs Sprint 9
   (cleaner styling, drill-down indicators)
2. **Hex literal ratchet chart**: 243 → 165 (Sprint 8) → target
   Sprint 9 close
3. **Aria count chart**: 4 → 35 → target Sprint 9 close (skip-to-content,
   aria-current)
4. **Mobile screenshots**: 320 / 375 / 414 Dashboard + Swap
5. **CI scorecard**: hex-ratchet runs / failed PRs / e2e cases

**Talking points**:
- "Sprint 8's investment is compounding — Sprint 9 added X more
  tests, dropped hex by Y, with no extra hardening time."
- "The audit's hypothesis (1 sprint of UX hardening unlocks
  Sprint 10 launch-ready) is validated."

### 3.6 Risk + acceptance scorecard (28:00–33:00, 5 min)

- RISK-REGISTER diff table (Sprint 8 → Sprint 9): 6 closed, 2 new
- Sprint 9 §5 hard-gate scorecard: X of 10 closed
- Stretch scorecard
- Carry-over to Sprint 10

### 3.7 Sprint 10 preview — F-25 SberID outline (33:00–38:00, 5 min)

**Driver**: PO (with Sber Online BU rep in audience).

Slide deck (4 slides):
1. **The asymmetric retail acquisition**: 100M Sber Online users vs
   ~6k current DLMM users
2. **Integration shape**: SberID OIDC bus we already have (Sprint 7
   #5.13 stub-against-defaults pattern works) + Sber Online deep-link
   → DLMM signup with pre-filled profile
3. **Expected conversion**: 0.1% pessimistic = 100K new users;
   1% achievable with right product placement = 1M
4. **Ask of Sber Online BU**: contract negotiation start + integration
   sandbox access by Sprint 10 mid

**Talking points**:
- "We have the auth bus, the JWT revocation, the audit log. The
  hardening is done. We can integrate within Sprint 10-11 if you
  open the door."

### 3.8 Retro top-3 + commitment (38:00–40:00, 2 min)

- 3 Liked / 3 Lacked from Sprint 9 (drafted Day 9 by the team)
- Top 3 Sprint 10 commitments (from kickoff Sprint 10 draft 9.D)

## 4. Pre-demo checklist (Day 9, T-1)

- [ ] All Sprint 9 commits squashed/merged to main
- [ ] Demo branch tagged `demo-sprint-9` for rollback
- [ ] `scripts/demo-otc-trade.ps1` rehearsed end-to-end in staging
- [ ] `scripts/demo-ysrub-cycle.ps1` rehearsed end-to-end (new this sprint)
- [ ] Pre-recorded video of each segment (5x ~5min) in `docs/demo/sprint-9/`
- [ ] Postman collection for API tiers checked in to `docs/demo/`
- [ ] Grafana dashboard JSON exported + saved to `docker/grafana/provisioning/dashboards/sprint-9.json`
- [ ] Q&A bank refreshed: Compliance bank, Tech bank, Commercial bank
- [ ] Audience invitations sent (PO Council + Sber Online BU rep — critical!)
- [ ] Backup MacBook + 1080p projector confirmed by Бутырский AV team
- [ ] Зум link tested with remote audience (Sber HQ VPN config)

## 5. Failure-mode summary

| Risk | Trigger | Fallback |
|---|---|---|
| Network drops mid-demo | Сбер HQ VPN flake | Switch to local laptop demo |
| OTC code didn't ship | Sprint 9 #6.1 slipped Day 9 | Pre-recorded video |
| YSRUB scheduler not firing | 7.2 not fully ready | Trigger manually via admin endpoint |
| API rate limiter wrong tier | Misconfig | Show Grafana panel; describe expected behaviour |
| Spasibo contract not closed | 9.C slipped | Pivot to design walkthrough (5min instead of live demo) |
| Sber Online BU rep no-show | Schedule conflict | Send F-25 deck async post-demo + schedule 1:1 |
| Audio cuts out remote | Zoom hiccup | Switch to backup Yandex Telemost |

## 6. Q&A bank (rehearse Day 9)

### Commercial
- Q: "Where's the run-rate trajectory after Sprint 9?"
  A: "600M ₽/yr at Sprint 8 close → 800M-1.0B forecast by Sprint 9 close
  if OTC + YSRUB land + 1 SLA contract signs. We'll have actual data
  in Sprint 10 retro."
- Q: "When does SBER10 index fund ship?"
  A: "Sprint 10-11 per current `SPRINT-PLAN.md`. Slid from Sprint 7
  per AU-1 rebalance; design ready, just waiting on sprint capacity."

### Compliance
- Q: "Does YSRUB need a separate ЦБ РФ filing as a money-market product?"
  A: "Per 9.B legal memo finalized this sprint — YSRUB is structured
  as repo-equivalent under existing ЦБ РФ frame. No new filing
  required. (Compliance lead: deep-dive available)"
- Q: "Where does the AML SAR auto-file live in the plan?"
  A: "F-17 in Sprint 12 per NEW-FEATURES-BACKLOG. Need RFM API
  access first (PO trek next sprint)."

### Technical
- Q: "How does the OTC RFQ aggregator scale to 50+ LPs?"
  A: "Sprint 9's MVP is sequential — single Redis-backed broadcast,
  20-second collection window. 50+ LPs needs Sprint 11 F-11 marketplace
  refactor to event-driven RFQ matching."
- Q: "What's the load profile on YSRUB daily yield?"
  A: "Currently 1 scheduler tick / day fans out to all holders.
  Sprint 10 will batch-credit if holder count > 10k (k6-verified
  threshold)."

### Brand / ecosystem
- Q: "What's the SberID integration timeline?"
  A: "Sprint 10 PO trek for contract; Sprint 11 code if Sber Online
  BU gives sandbox access. Realistic launch: late Q3 2026."

## 7. Post-demo

- [ ] Send recording + slide deck to all attendees within 24h
- [ ] Schedule 1:1 with Sber Online BU rep if F-25 interest expressed
- [ ] Schedule Sprint 9 retro for 2026-07-30 (Day-after, kickoff Sprint 10 same day)
- [ ] Update `SPRINT-PLAN.md` cumulative-revenue table with Sprint 9 actuals
- [ ] Update `RISK-REGISTER.md` — close Sprint 9 closures, add new emergents

---

*Recorded by: IT-lead + PO. Companion to `SPRINT-9-KICKOFF.md` (the
plan) + post-demo `SPRINT-9-ACCEPTANCE.md` (the close).*
