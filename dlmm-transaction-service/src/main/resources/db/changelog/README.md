# Liquibase changelog — read before adding files

Convention is documented in `docs/DB-MIGRATION-CONVENTION.md` at the repo root.

**TL;DR**:
- New tables (post-Sprint 1): CREATE TABLE in a changeset WITHOUT preConditions.
  No need to add to `docker/init-db.sql`.
- Adding columns / indexes / constraints: ALTER changeset WITHOUT preConditions.
- The existing 001-XXX changesets that mirror `init-db.sql` tables keep
  their `preConditions onFail="MARK_RAN"` wrapper. Do NOT touch them.
- Run smoke test on both fresh (`docker-compose down -v && up -d`) AND
  existing DB before merging.

Anti-patterns and full rationale: see the convention doc.
