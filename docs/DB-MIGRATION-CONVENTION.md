# DB Migration Convention

**Owner**: Backend lead.
**Status**: Sprint 3 #3.8 deliverable. Closes risk R#4.

> Why this doc exists: Sprint 1 introduced `<preConditions onFail="MARK_RAN">`
> on every CREATE TABLE changeset so that Liquibase and `docker/init-db.sql`
> can coexist without colliding on greenfield boots. That was correct as a
> tactical fix but it created an implicit convention nobody knew. R#4
> captures the risk that "future schema changes won't apply on existing
> DBs" — true for CREATE-style changesets, **not true** for properly-written
> ALTER changesets. This doc makes the convention explicit.

---

## The two paths

### Greenfield (fresh DB, e.g. `docker-compose down -v && up -d`)

1. Postgres entrypoint executes `/docker-entrypoint-initdb.d/*.sql` in
   alphabetical order. Today that's:
   - `01-init-db.sql` — full schema (CREATE TABLE for 12 tables) + seed data
   - `02-extended-assets.sql` — extra tokens + pools
   - `03-seed-trading-history.sql` — 30 days of transactions, LP positions
2. Each service starts. Liquibase scans its `db/changelog/master.xml`.
3. For every CREATE-TABLE changeset, the `<preConditions
   onFail="MARK_RAN"><not><tableExists/></not></preConditions>` wrapper
   sees the table already exists → MARK_RAN. No conflict.
4. For every ALTER-style changeset (no preCondition), Liquibase applies
   the ALTER normally.

### Existing DB (any non-greenfield deployment)

1. Postgres entrypoint does NOT re-run init scripts.
2. Each service starts. Liquibase scans changesets.
3. CREATE-TABLE changesets — MARK_RAN as before (idempotent).
4. New ALTER changesets — apply cleanly.

**The convention only fails** if someone adds a new CREATE-TABLE
changeset for a table not in `init-db.sql`. Then greenfield doesn't
create the table at all (Liquibase MARK_RAN'd it because the
`<not><tableExists/></not>` preCondition was wrongly inherited from
copy-paste). See "Anti-pattern" below.

---

## Rules

### ✅ DO

1. **For schema changes to existing tables**: write an ALTER changeset
   **without** `<preConditions>`. Example:

   ```xml
   <changeSet id="006-add-trading-status-to-pools" author="dlmm">
       <addColumn tableName="liquidity_pools">
           <column name="trading_status" type="VARCHAR(20)"
                   defaultValue="ACTIVE">
               <constraints nullable="false"/>
           </column>
       </addColumn>
   </changeSet>
   ```

   No preCondition needed. Runs on greenfield (Liquibase adds the
   column after init-db.sql created the table) and on existing DBs
   (Liquibase adds the column to the live table). Idempotent because
   Liquibase tracks applied changesets in `DATABASECHANGELOG`.

2. **For genuinely new tables introduced after Sprint 1**: same as
   above — CREATE TABLE **without** preCondition. Add a matching
   `CREATE TABLE IF NOT EXISTS` block to `docker/init-db.sql` so
   greenfield boot still works (Postgres applies it first, Liquibase
   MARK_RAN's because the table exists). The IF NOT EXISTS clause is
   what makes this safe.

   Then add the equivalent `<preConditions>` block to the new
   changeset following the existing convention. This is the only
   case where preConditions is correct.

3. **For seed/data changes (INSERT, UPDATE, DELETE)**: write a Liquibase
   `<sql>` or `<insert>` changeset. Optionally use Liquibase contexts
   (`<changeSet context="seed">`) so production deploys can skip seed.

### ❌ DON'T

1. **Don't add CREATE-TABLE changesets without also updating
   `init-db.sql`** — greenfield will boot with neither the table from
   init-db nor the table from Liquibase (because the preCondition
   pattern got copy-pasted without thinking).

2. **Don't drop the preCondition wrapper on existing changesets
   001-005** (the ones that match init-db.sql tables). Removing them
   means Liquibase will try to CREATE TABLE which already exists,
   and crash with "relation already exists".

3. **Don't seed via raw SQL in `init-db.sql` for new business data**.
   It bypasses Liquibase's audit trail
   (`DATABASECHANGELOG.MD5SUM`). Use Liquibase `<sql>` or `<insert>`
   instead.

4. **Don't use `ddl-auto: update` in `application.yml`**. Hibernate
   would silently alter the schema on its own, racing with Liquibase.
   We use `ddl-auto: validate` everywhere — Hibernate just verifies
   the schema matches the entities.

---

## Anti-pattern (what NOT to write)

```xml
<!-- BAD: copy-pasted preCondition on a new table.
     Greenfield: init-db.sql doesn't create it → tableExists=false →
     <not> evaluates true → changeset would normally run, except the
     copy-paste author left onFail=MARK_RAN with <not> inverted. Either
     way the developer didn't think it through. -->
<changeSet id="009-create-new-thing-table" author="dlmm">
    <preConditions onFail="MARK_RAN">
        <not><tableExists tableName="new_thing"/></not>
    </preConditions>
    <createTable tableName="new_thing">
        ...
    </createTable>
</changeSet>
```

The CORRECT pattern for a genuinely new table after Sprint 1:

**Option A** (preferred for Sprint 3+ tables — no init-db.sql sync needed):
```xml
<changeSet id="009-create-new-thing-table" author="dlmm">
    <createTable tableName="new_thing">
        ...
    </createTable>
</changeSet>
```
No init-db.sql entry. Liquibase creates the table on first apply, regardless
of greenfield or existing DB.

**Option B** (if you need the table in greenfield seed for demo data):
```xml
<changeSet id="009-create-new-thing-table" author="dlmm">
    <preConditions onFail="MARK_RAN">
        <not><tableExists tableName="new_thing"/></not>
    </preConditions>
    <createTable tableName="new_thing">
        ...
    </createTable>
</changeSet>
```
**Plus** add a matching `CREATE TABLE IF NOT EXISTS new_thing (...)` to
`docker/init-db.sql`. Greenfield: init-db.sql creates it, Liquibase
MARK_RAN's. Existing DB: Liquibase creates it (preCondition lets it run).

Use Option A unless you have a hard need for greenfield seed data on the
new table.

---

## Verification checklist for any PR with schema change

- [ ] Did Liquibase changelog get a new file with a unique numeric prefix?
- [ ] Does the changeset have a unique `id` (no collision with other files)?
- [ ] Is the change additive? (renames/drops require multi-step: add new,
      migrate data, drop old over 2 deployments).
- [ ] If CREATE TABLE: is there a matching `init-db.sql` IF NOT EXISTS
      block? (Option B above)
- [ ] If ALTER: did you avoid the preCondition wrapper?
- [ ] Did the PR include a smoke test against an existing DB
      (`docker-compose up -d` without `-v`) AND a greenfield (`down -v`
      then `up -d`)?
- [ ] If table affects JPA entity: is `@Column` / `@Table` annotation
      in sync? (Hibernate `validate` will refuse to start otherwise).

---

## Example: when this gets exercised

Sprint 3 #3.3 (custody fee scheduled job) needs `user_balances` to track
the last accrual timestamp so the cron can skip already-accrued days.

The right changeset for the token-service `db/changelog/`:

```xml
<changeSet id="004-add-custody-fee-tracking-to-user-balances" author="dlmm">
    <addColumn tableName="user_balances">
        <column name="last_custody_fee_at" type="TIMESTAMP">
            <!-- nullable: existing rows haven't been accrued yet,
                 the job uses COALESCE(last_custody_fee_at, created_at). -->
        </column>
    </addColumn>
</changeSet>
```

No preCondition. No `init-db.sql` change. Runs on greenfield AND existing
DB cleanly. This is the new normal.

---

## TODO (Sprint 4+)

The full "split" path — drop `init-db.sql` entirely and move seed data to
Spring `CommandLineRunner` beans per service — would let us remove ALL
preCondition wrappers and stop relying on the `IF NOT EXISTS` defense.
Effort: ~5 days across 7 services. Not blocking, currently parked.

Triggering condition: when we have more than 3 new ALTER changesets
shipped on top of the Sprint 1 baseline, the convention will have proven
itself or revealed friction. Decide then.

---

*Created 2026-05-17 for Sprint 3 #3.8. Read-required for any PR
touching `*/db/changelog/`.*
