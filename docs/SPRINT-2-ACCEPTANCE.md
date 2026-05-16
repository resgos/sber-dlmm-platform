# Sprint 2 — Acceptance Protocol

**Date**: 2026-05-16
**Decision**: ✅ **ACCEPTED** (with 2 known defects scheduled for Sprint 3)
**Stakeholders present**: PO, IT-lead, SA, BA, Compliance lead, SRE on-call

---

## Acceptance verdicts per deliverable

| # | Deliverable | Verdict | Sign-off | Notes |
|---|---|---|---|---|
| 1 | Pool-engine outbox + cross-service segregation | ✅ ACCEPTED | Compliance, SA | Live demo: swap → +1 pool-engine + 2 token-service events. Caught dispatcher mid-flight (unpub=1,2 → 0 in 3s). Service-column filter confirmed. |
| 2 | CI/CD pipeline (3 workflows) | ✅ ACCEPTED with condition | PO, SRE | Workflows valid, deployed. First green build to be verified post-merge — formal gap, not blocker. |
| 3 | k6 load test baseline | ✅ ACCEPTED | PO, BA, SA | Honest first-cut: 34 RPS sustained, p99 23s on /pools @ 300 VUs. All 5 thresholds fired by design. Diagnosis documented. |
| 4 | Kafka consumer lag healthcheck | ✅ ACCEPTED | SRE, Compliance | Live drill: injected 1500 messages → DOWN (lag=1500) → UP (lag=0) in 12s. |
| 5 | Prometheus + Grafana stack | ✅ ACCEPTED with defect | PO, SRE, BA | 8 of 9 targets UP. Gateway target DOWN (404 — reactive actuator needs separate config). Scheduled for Sprint 3 #3.12. |
| 6 | Pre-warm cold-start | ✅ ACCEPTED | PO, IT-lead | Logs: "Startup warm complete in 808 ms" (pool-engine), 803 ms (admin-bff). Dashboard first-call: 1.1s (vs Sprint 1's 2.9s) — 3x improvement. |
| 7 | CORS dev/prod split | ✅ ACCEPTED | Compliance | Live: localhost:3000 → 200 + ACA-Origin; evil.com → 403. application-prod.yml carries Sber-owned domains. |

---

## Sprint-level acceptance criteria check

From Sprint 1 close:

| Criterion | Target | Actual | ✓/✗ |
|---|---|---|---|
| Swap latency | < 2 s warm | 154–223 ms | ✓ |
| Dashboard latency | < 2 s warm | 100–148 ms | ✓ |
| Kill-and-restore drill | Service recovers | token-service & Kafka drills both PASS | ✓ |

All Sprint-level criteria **met**.

---

## Known defects (carried into Sprint 3)

1. **CI/CD first green build** — workflows exist but first run pending merge. Verify Sprint 3 day 1 (PO action).
2. **Gateway `/actuator/prometheus`** returns 404 — reactive actuator needs separate config. Scheduled Sprint 3 task #3.12 (SRE).

---

## What got better since Sprint 1

| Aspect | Sprint 1 close | Sprint 2 close |
|---|---|---|
| Critical risks open | 4 | 1 (#4 Liquibase) |
| Lost-event window | open (token-service only fixed) | **closed both services** |
| Cold-start penalty | 2.9 s | 1.1 s (3× faster) |
| Observability | /actuator/health per service | + Prometheus scrape 8 services + Grafana dashboard |
| RPS budget knowledge | unknown | documented baseline + Sprint 3 plan |
| CI gating | none | 3 workflows live |
| CORS for prod | dev-only YAML | profile-driven split, prod overrides ready |

---

## Cross-functional signals (not blocking, captured for Sprint 3 planning)

- **BA**: "Acceptance ceremony itself surfaced that ivanov's SBTC was
  depleted from Sprint 2 load tests. We need test-data reset hooks
  before Sprint 3 demo prep starts."
  → Added as Sprint 3 ad-hoc task: `scripts/reset-demo-data.ps1`.
- **Compliance**: "Outbox solves dual-write. Next concern is what
  happens if Sber Treasury (Sprint 3 #3.B) becomes dominant LP and
  same-pool row lock serialises everyone."
  → Already tracked in Sprint 3 #3.10 (analysis) → Sprint 4 #4.7 (fix).
- **SRE**: "Grafana port 3030 from host occasionally flakes (Docker
  Desktop network proxy). Dashboard URL works inside the network.
  Not blocking for demo but worth a sentence in the demo script."
  → Add to DEMO-SCRIPT.md backup-if-breaks section.

---

*Signed off: PO + IT-lead + SA + BA + Compliance + SRE.*
*Sprint 3 kickoff immediately following. See `docs/SPRINT-3-KICKOFF.md`.*
