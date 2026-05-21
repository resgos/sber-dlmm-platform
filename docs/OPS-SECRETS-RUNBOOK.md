# Secrets management runbook (TD-2)

> Sprint 9-DS-r4. Backlog TD-2 in `docs/BACKLOG-2026-05-21.md`.
>
> This file is the production checklist for moving secrets out of
> `docker/.env` and into a proper vault (Vault / AWS Secrets Manager
> / Sber-internal KMS). The code already fails fast on missing env
> vars (`${JWT_SECRET:?required}` etc.) — what's missing is the
> *operational* discipline around where those values live.

---

## What lives in `docker/.env` today

| Var | Used by | Risk if leaked |
|---|---|---|
| `DB_PASSWORD` | postgres + 8 Spring services | Full DB read/write. Highest blast radius. |
| `JWT_SECRET` | gateway + 7 downstream services | Token forgery → impersonate any user. Same blast radius as DB_PASSWORD. |
| `DB_USER` | postgres + 8 Spring services | Low — username is not a secret. |

Everything else (`DB_HOST`, `DB_PORT`, Kafka topics, etc.) is
non-sensitive config and can stay in version control.

## Why this is dev-only

`docker/.env` is gitignored, but:

1. Dev defaults (`dlmm_secret_dev_only`,
   `super-secret-jwt-key-for-dlmm-platform-256-bit-min-dev-only`)
   are still in `.env.example` — anyone who clones the repo and
   forgets to overwrite ships with known credentials.
2. There's no rotation story. A leaked `.env` file means manual
   `docker-compose down` + edit + restart on every host.
3. The same secret is used by 8 services. We can't grant
   per-service or per-environment scope.
4. No audit log of who read or rotated the secret.

## Production rollout — three options, picked in this order

### Option A (RECOMMENDED for Sprint 10): Sber-internal Vault (HashiCorp Vault)

Sber has an internal Vault cluster already in use by SBOL / SBBOL.
Pattern:

1. Operator provisions a Vault path `secret/dlmm/<env>/{db_password,jwt_secret}`.
2. Each service gets an AppRole with `role_id` (cleartext, baked
   into container env) and `secret_id` (one-time wrapper,
   regenerated on every pod restart).
3. Spring Boot reads via `spring-cloud-vault` (Spring Cloud Vault
   3.1.x → Spring Boot 3.2 compatible) — values land as if they
   were `application.yml` properties.
4. Rotation: change in Vault → `lease_duration` expires → Spring
   re-fetches on next renewal. No restart needed for `db_password`
   (Hikari refreshes its pool); JWT secret rotation requires
   rolling restart (sessions stay valid because we keep both old +
   new key for the grace window — Spring Security's
   `JwtAuthenticationConverter` chain supports key rotation).

**Effort estimate:** 3-5 days including Sber-internal Vault
onboarding. Vault address + AppRole creation comes from the SRE
team; the code change is one Maven dep (`spring-cloud-starter-vault-config`)
and a `bootstrap.yml` per service.

### Option B (Fallback if Vault timeline slips): Docker Swarm / Compose secrets

Docker Compose 3.9 supports `secrets:` blocks that mount values
into `/run/secrets/<name>` rather than env vars. Spring reads via
`spring.config.import=file:/run/secrets/jwt_secret`.

**Pros:** zero infrastructure dependency, ship today.
**Cons:** still file-based; no audit, no rotation, no per-service
scope. Halfway-house only.

### Option C (Cloud-only fallback): AWS Secrets Manager / KMS

Only applicable if a non-Sber-cloud deployment is on the roadmap.
Skipped for the typical Sber Cloud / Pangolin target.

---

## Bootstrap-checklist for any production environment

Before signing off a production cluster:

- [ ] `docker/.env` is **not** present on any host (Vault provides
      values at runtime).
- [ ] `JWT_SECRET` is ≥ 48 bytes random (`openssl rand -base64 48`).
- [ ] `DB_PASSWORD` is ≥ 32 chars, mixed case + digits + symbols.
- [ ] Each environment (dev/staging/prod) has a **different**
      `JWT_SECRET` — same value across envs means a dev token can
      be replayed against prod.
- [ ] Vault audit log streams to Splunk / Loki with retention ≥ 90
      days.
- [ ] Rotation cadence documented: `JWT_SECRET` every 90 days,
      `DB_PASSWORD` every 180 days, ad-hoc on suspected leak.
- [ ] Rolling-restart runbook tested in staging: gateway re-issues
      tokens with new key; old tokens valid for the grace window;
      no user logged out.
- [ ] Break-glass procedure exists: if Vault is unreachable, the
      service should fail closed (current `${JWT_SECRET:?required}`
      ensures this) — not fall back to a dev default.

---

## Rotation runbook

### Rotating `JWT_SECRET`

1. Generate new secret: `openssl rand -base64 48`.
2. Write to Vault under `secret/dlmm/<env>/jwt_secret_next`.
3. Update every service's `application.yml` to read both
   `jwt_secret` and `jwt_secret_next` — `JwtAuthenticationConverter`
   tries both for verification, signs new tokens with the *new*
   one. (Future work — code change for the `_next` key is a
   Sprint 10 task; **today** rotation requires a rolling restart
   with a hard cutover and ~5min of cached-token rejections.)
4. After 30 days (longest token TTL × 2 safety margin), promote
   `jwt_secret_next` → `jwt_secret` and remove the old value.
5. Audit: confirm no `JwtException` spikes in Grafana dashboard
   "Auth health".

### Rotating `DB_PASSWORD`

1. `psql` as superuser: `ALTER USER dlmm WITH PASSWORD '<new>';`.
2. Update Vault entry.
3. Trigger Hikari pool refresh: each service exposes
   `/actuator/restart-datasource` (Sprint 10) or, today, requires
   a rolling restart of all 8 backend services.
4. Audit: confirm no `Connection refused` or `password
   authentication failed` log lines.

---

## What we explicitly are NOT doing

- **Per-user JWT signing keys.** Single platform secret is fine for
  the deployment shape (one tenant, one cluster). Per-user keys
  would push us into key-management territory without payoff.
- **Hardware Security Module (HSM)** for the JWT secret. Sber HSMs
  exist but are reserved for SBOL-tier signing where private-key
  ops happen in the box. Our throughput (~1000 signs/sec) is two
  orders of magnitude below what would justify the integration.
- **Encryption at rest in `docker/.env`** (e.g. `git-crypt`). Adds
  ops complexity without solving the audit-log or rotation gaps.
  Skip directly to Vault.

---

## Related work

- `docker/.env.example` — template that lives in the repo.
- `dlmm-gateway/src/main/resources/application.yml` —
  `dlmm.jwt.secret: ${JWT_SECRET:?required}` (fails fast on
  missing).
- `dlmm-common/.../JwtTokenProvider.java` — token signing.
- `OPS-DB-BACKUP-RUNBOOK.md` (TD-6) — companion runbook for DB
  snapshot strategy.
