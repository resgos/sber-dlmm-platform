# TD-1 — init-db.sql → Liquibase cleanup plan (2026-05-31)

> Produced by a planning subagent. The plan, not code. Touches DB bootstrap of a LIVE demo.

## Headline
The Liquibase changesets are **already a complete, self-sufficient schema** — every table in `init-db.sql` has a matching `createTable` changeset, and every post-Sprint-1 column has a later `addColumn` changeset. The MARK_RAN preConditions only stop Liquibase colliding with init-db on greenfield. **No table would vanish on removal** — the risk is column-drift, ordering, and one rename.

## Three hard blockers
1. **`notifications.read → is_read` rename.** init-db.sql ships `read`; changeset 001 also creates `read`; 002 renames to `is_read` guarded on seeing `read`. The entity wants `is_read`. Any reshuffle here desyncs the column name → notification-service boot crash. **Fix changeset 001 + init-db together FIRST.**
2. **MARK_RAN removal is not free.** A precondition-less `createTable` on an *existing* DB (table already there) throws "relation already exists" → crash-loop. Only valid on a fresh `down -v` volume. So it must be the LAST step.
3. **Seed INSERTs run at the Postgres entrypoint, BEFORE any service/Liquibase.** init-db.sql + 02/03/04 INSERT into tables at container-init time. If init-db stops creating tables, those INSERTs hit non-existent tables → whole stack fails to init. (No DB-level cross-service FKs exist, so table *ordering* between services is not a constraint — the seed→table dependency is.)

## Two tracks
- **Track B (recommended, demo-safe, incremental):** keep the CREATE TABLEs, but remove the *duplicated columns* from init-db.sql (version, protocol accumulators, counterparty limits, last_login_at, initial_deposit, pool_engine_tx_id, reviewed_*, actor_type) so Liquibase's `addColumn` changesets become the single source of truth. Eliminates the drift risk (R#4) with zero ordering hazard. Do one table per commit, each gated by the from-scratch `schema-drift` CI. **Plus B0: fix the notifications rename.**
- **Track A (full TD-1, post-demo only):** move seed DML out of the entrypoint (either unmount 02/03/04 + re-apply post-boot like 05–11, or convert to `context="seed"` changesets ≈ the 5-day item), THEN drop CREATE TABLEs, THEN remove MARK_RAN — each step validated on a fresh volume. **Not demo-window-safe** (needs a full from-scratch rebuild; MARK_RAN removal crash-loops an existing DB).

## Recommendation
**Track B now** (B0 + the 5 drift-prone tables) — safe, reversible per-commit, kills the "two sources of truth" failure mode. **Defer Track A** to a post-demo window with the `schema-drift` CI (`docker compose up --wait` from a clean volume) as the mandatory gate.

Top hazards: notifications rename; a seed INSERT naming a removed column → entrypoint abort; MARK_RAN removal on a non-fresh DB; `outbox_events` dual-creator (token 003 + pool-engine 005, intentional); seeds 05–11 ordering.

Critical files: `docker/init-db.sql`, `docker/docker-compose.yml` (entrypoint mounts), `dlmm-notification-service/.../001+002` changesets, each service's `master.xml`, `.github/workflows/schema-drift.yml` (the verification harness).
