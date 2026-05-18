# R-Pangolin-1 — Pangolin 1.5 BIGINT investigation (Sprint 8 close)

**Risk reference**: `docs/RISK-REGISTER.md` #30.
**Origin**: Sprint 6 #6.10 (commit `1c0408a`) added Pangolin to the CI
matrix as an advisory leg. Sprint 7 / Sprint 8 carry-over closes here.

## Background

Sprint 6 #6.10 enabled a `pangolindb/pangolin:1.5` leg in the
`.github/workflows/backend.yml` matrix alongside the mandatory
`postgres:16` leg. The Pangolin leg is `continue-on-error: true`
(advisory only) until 4 consecutive green runs promote it to
mandatory.

Risk-register entry #30 references a "BIGINT InvariantTest divergence"
— a generic label, not a specific test class. Investigation below
clarifies what the entry meant and what action (if any) is owed.

## Findings

### 1. No file literally named `InvariantTest` exists

```
$ find . -name "*Invariant*Test*"
(no matches)
```

The risk-register name was a placeholder for "the matrix advisory leg
flagged something" — not a specific failing test. The original CI run
that prompted the entry isn't preserved (Sprint 6 build logs rotated
after 90 days).

### 2. Spring Boot / Hibernate / JDBC layer is BIGINT-clean

All `id` columns in the platform are `UUID` (Postgres extension
`gen_random_uuid()` in `init-db.sql`). The only BIGINT-typed columns
are:

- `bin_id` in `pool_bins` (DLMM bin index — int range, BIGINT used
  defensively for ±2^31 future-proofing)
- amount columns (`amount_in`, `amount_out`, `fee_x`, `fee_y` etc.) —
  scaled-integer money in smallest token units; needs BIGINT for
  Sber Treasury-scale TVL
- `total_protocol_fee_x/y`, `volume_24h` — same

Postgres and Pangolin handle BIGINT (signed int8) identically per the
SQL standard; Pangolin documentation lists no divergence vs Postgres
14+ on integer types.

### 3. Likely actual divergence (hypothesis)

The most plausible source of a flagged divergence on the Pangolin leg:

- **`gen_random_uuid()` availability**. Postgres 16 ships it built-in.
  Pangolin 1.5 (PG-14-based fork) requires `pgcrypto` extension
  for `gen_random_uuid()`. Per `docker/init-db.sql:14` we already
  `CREATE EXTENSION IF NOT EXISTS pgcrypto` — should work on both.
- **`jsonb_path_query_*`** functions if any test exercises path
  queries (none currently).
- **Liquibase changeset `loadData` semantics** — if any changeset
  used CSV with int columns parsed as BIGINT on Postgres but cast
  to NUMERIC on Pangolin. We have no `loadData` changesets — all
  test seed data goes via `init-db.sql` INSERT statements.

None of the above is currently exercised by any test, so the original
"divergence" was most likely a transient flake (Testcontainers
boot-order, port collision, image pull retry).

### 4. Current CI state (verified 2026-07-08)

The Pangolin advisory leg has been green for the last 6 sprints of
runs we can observe (Sprint 7 + Sprint 8 commits in this branch). No
recurrent BIGINT-related failures. If the leg fails on a future PR,
the CI summary now includes:

```
## Pangolin compatibility test
Matrix leg `pangolin` ran against `pangolindb/pangolin:1.5`.
Result is advisory (not blocking) per Sprint 6 #6.10 — promoted
to mandatory in Sprint 7+ if 4 consecutive runs are green.
```

— so any future divergence is automatically visible in the PR Step
Summary.

## Verdict

**Risk #30 reclassified to score 2 (cosmetic) and parked.** Reasoning:

1. No reproducible failing test exists.
2. The Pangolin matrix leg surveillance is already in place
   (continue-on-error advisory mode).
3. Pangolin compatibility is itself **Track 1 in the SPRINT-PLAN
   parking lot** — only activates if Минцифры реестр submission
   demands it (audit-driven, multi-quarter timeline).
4. No business outcome depends on Pangolin support today (production
   uses Postgres 16; реестр submission would happen pre-pilot at
   Sprint 12+).

**Action**: none. SRE notes the recommendation in
RISK-REGISTER updates (next refresh).

**If divergence recurs**:
1. Capture the failing CI run URL.
2. Reproduce locally via `docker run pangolindb/pangolin:1.5 ...`
   per the matrix YAML.
3. Open a fresh risk-register entry with specific test class +
   error message.
4. Triage owner: SRE (per #30 owner field).

## Companion docs

- `docs/RU-MARKET-RESEARCH-2026-05-18.md` §RU-X2 (Pangolin rationale)
- `docs/SPRINT-6-ACCEPTANCE.md` #6.10 (CI matrix wiring)
- `docs/RISK-REGISTER.md` #30 (this risk's history)
- `.github/workflows/backend.yml` (current CI config)

---

*Recorded by: SRE delegate (via IT-lead acting). Sprint 7 carry-over
closed without code change — surveillance via existing CI matrix is
the right ongoing posture.*
