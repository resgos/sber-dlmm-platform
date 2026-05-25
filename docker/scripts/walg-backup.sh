#!/usr/bin/env bash
# walg-backup.sh — production-grade Postgres backup via WAL-G to S3 (TD-6).
#
# Sprint 10+ replacement for the dev-grade pg-snapshot.sh. Uses WAL-G to
# push a base backup (`wal-g backup-push`) of the running Postgres data
# directory into S3-compatible storage. Pair with continuous WAL archiving
# (see TODO below) for point-in-time recovery (PITR).
#
# This script is cron-runnable from any host that can `docker exec` into
# the dlmm-postgres container. See docker-compose.walg.yml for the
# WAL-G sidecar container that runs this on a schedule.
#
# Reads env from docker/.env.walg (gitignored). Required vars:
#   WALG_S3_PREFIX            — e.g. s3://sber-dlmm-prod-backups/cluster1
#   AWS_ACCESS_KEY_ID         — S3 credentials
#   AWS_SECRET_ACCESS_KEY     — S3 credentials
#   AWS_ENDPOINT              — S3 endpoint (Sber Cloud Object Storage URL)
# Optional vars:
#   WALG_COMPRESSION_METHOD   — default lz4 (good speed/size trade-off)
#   AWS_REGION                — default ru-central1
#   WALG_BACKUP_CONTAINER     — default dlmm-postgres
#   WALG_BACKUP_USER          — default dlmm
#   WALG_BACKUP_DB            — default dlmm
#   WALG_PGDATA               — default /var/lib/postgresql/data
#   WALG_BIN                  — default wal-g (must be on PATH inside container)
#
# Flags:
#   --dry-run                 — validate env + wal-g availability, no push
#   --help                    — show usage
#
# Exit codes:
#   0  success
#   1  wal-g push failed
#   2  container not running
#   3  required env var missing
#   4  wal-g binary not available
#   5  invalid usage
#
# Cron line (host with docker access):
#   30 2 * * *  /opt/dlmm/docker/scripts/walg-backup.sh >> /var/log/dlmm-walg.log 2>&1
#
# Off-peak 02:30 — earlier than pg-snapshot.sh (03:17) and the outbox
# cleanup (03:17), so contention is minimal. WAL-G base backups read the
# whole data directory, so we want them to finish before the morning
# read-heavy traffic ramps up.
#
# TODO (follow-up PR): production needs Postgres `archive_mode = on` +
# `archive_command = 'wal-g wal-push %p'` for true PITR. Cannot toggle on
# the live dev stack (requires restart, conflicts with running compose).
# This MVP captures base backups only; WAL stream is empty until the
# migration PR lands.

set -euo pipefail

usage() {
    cat <<'EOF'
walg-backup.sh — push a Postgres base backup to S3 via WAL-G.

Usage:
  walg-backup.sh [--dry-run] [--help]

Env (loaded from docker/.env.walg if present):
  WALG_S3_PREFIX            REQUIRED  s3://bucket[/prefix]
  AWS_ACCESS_KEY_ID         REQUIRED  S3 access key
  AWS_SECRET_ACCESS_KEY     REQUIRED  S3 secret key
  AWS_ENDPOINT              REQUIRED  S3 endpoint URL
  WALG_COMPRESSION_METHOD   optional  default: lz4
  AWS_REGION                optional  default: ru-central1
  WALG_BACKUP_CONTAINER     optional  default: dlmm-postgres
  WALG_BACKUP_USER          optional  default: dlmm
  WALG_BACKUP_DB            optional  default: dlmm
  WALG_PGDATA               optional  default: /var/lib/postgresql/data
  WALG_BIN                  optional  default: wal-g

Exit codes: 0 success | 1 push failed | 2 container down | 3 env missing
            | 4 wal-g unavailable | 5 invalid usage
EOF
}

# ---- parse flags -----------------------------------------------------
DRY_RUN=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=1; shift ;;
        --help|-h) usage; exit 0 ;;
        *) echo "[walg-backup] ERROR: unknown flag: $1" >&2; usage >&2; exit 5 ;;
    esac
done

# ---- log helper ------------------------------------------------------
# Cron-friendly: ISO 8601 UTC timestamp + tag, single line per event so
# downstream log shippers (Loki/Splunk) can grep without multiline
# parsing. The [walg-backup] tag mirrors pg-snapshot.sh's convention.
log() {
    echo "[walg-backup] $(date -u +%Y-%m-%dT%H:%M:%SZ) $*"
}

# ---- load .env.walg --------------------------------------------------
# Resolve relative to this script so cron invocations from any cwd
# still find the env file.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${WALG_ENV_FILE:-${SCRIPT_DIR}/../.env.walg}"

if [[ -f "$ENV_FILE" ]]; then
    log "loading env from ${ENV_FILE}"
    # shellcheck disable=SC1090
    set -a; source "$ENV_FILE"; set +a
else
    log "no ${ENV_FILE} found — relying on inherited env"
fi

# ---- validate required env (fail fast) -------------------------------
# We check these BEFORE touching docker / wal-g so the error message
# tells the operator exactly what's missing.
missing=()
for var in WALG_S3_PREFIX AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_ENDPOINT; do
    if [[ -z "${!var:-}" ]]; then
        missing+=("$var")
    fi
done
if [[ ${#missing[@]} -gt 0 ]]; then
    log "ERROR: wal-g config missing required env var(s): ${missing[*]}" >&2
    log "       define them in ${ENV_FILE} (see .env.walg.example for template)" >&2
    exit 3
fi

# ---- defaults --------------------------------------------------------
export WALG_COMPRESSION_METHOD="${WALG_COMPRESSION_METHOD:-lz4}"
export AWS_REGION="${AWS_REGION:-ru-central1}"
WALG_BACKUP_CONTAINER="${WALG_BACKUP_CONTAINER:-dlmm-postgres}"
WALG_BACKUP_USER="${WALG_BACKUP_USER:-dlmm}"
WALG_BACKUP_DB="${WALG_BACKUP_DB:-dlmm}"
WALG_PGDATA="${WALG_PGDATA:-/var/lib/postgresql/data}"
WALG_BIN="${WALG_BIN:-wal-g}"

# ---- preflight: container running ------------------------------------
# `docker ps -q -f name=^<name>$` returns the container id if running,
# empty otherwise. Same idiom pg-snapshot.sh uses.
if ! command -v docker >/dev/null 2>&1; then
    log "ERROR: docker not on PATH" >&2
    exit 2
fi
if [[ -z "$(docker ps -q -f "name=^${WALG_BACKUP_CONTAINER}$" 2>/dev/null || true)" ]]; then
    log "ERROR: container '${WALG_BACKUP_CONTAINER}' is not running" >&2
    log "       (start with: docker-compose up -d postgres)" >&2
    exit 2
fi

# ---- preflight: wal-g binary available -------------------------------
# Production sidecar (docker-compose.walg.yml) ships wal-g as the
# container's main binary. Validate it's actually invokable before we
# do anything destructive — better to fail at 02:30:01 than at
# 02:30:45 with the cluster mid-checkpoint.
if ! docker exec "${WALG_BACKUP_CONTAINER}" sh -c "command -v ${WALG_BIN}" >/dev/null 2>&1; then
    log "ERROR: wal-g binary ('${WALG_BIN}') not installed in container '${WALG_BACKUP_CONTAINER}'" >&2
    log "       use docker-compose.walg.yml override (sidecar ships wal-g) or" >&2
    log "       install wal-g into the postgres image (production path)" >&2
    exit 4
fi

log "preflight OK: container=${WALG_BACKUP_CONTAINER} pgdata=${WALG_PGDATA}"
log "  WALG_S3_PREFIX=${WALG_S3_PREFIX}"
log "  WALG_COMPRESSION_METHOD=${WALG_COMPRESSION_METHOD}"
log "  AWS_ENDPOINT=${AWS_ENDPOINT}"

# ---- dry-run exit ----------------------------------------------------
if [[ "$DRY_RUN" == "1" ]]; then
    log "dry-run: skipping wal-g backup-push"
    log "SUCCESS dry-run"
    exit 0
fi

# S3 + WAL-G env to forward into the container — built once, reused
# by both backup-push and backup-list below. Credentials live only
# in this process's memory + the docker daemon's exec request, never
# in the image layer.
walg_env=(
    -e "WALG_S3_PREFIX=${WALG_S3_PREFIX}"
    -e "AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}"
    -e "AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}"
    -e "AWS_ENDPOINT=${AWS_ENDPOINT}"
    -e "AWS_REGION=${AWS_REGION}"
    -e "WALG_COMPRESSION_METHOD=${WALG_COMPRESSION_METHOD}"
)

# ---- run wal-g backup-push -------------------------------------------
# `wal-g backup-push <pgdata>` starts a low-priority pg_start_backup,
# copies the data dir to S3 (concurrent uploads, LZ4-compressed
# per-file), then pg_stop_backup. Postgres stays online the whole
# time. We invoke INSIDE the postgres container so wal-g can read
# the data dir directly and issue SQL via the local socket.
start_ts=$(date +%s)
log "starting wal-g backup-push (this can take minutes for large clusters)"

if ! docker exec \
        "${walg_env[@]}" \
        -e PGHOST=/var/run/postgresql \
        -e "PGUSER=${WALG_BACKUP_USER}" \
        -e "PGDATABASE=${WALG_BACKUP_DB}" \
        "${WALG_BACKUP_CONTAINER}" \
        "${WALG_BIN}" backup-push "${WALG_PGDATA}" 2>&1 | sed "s/^/[walg-backup]   /"; then
    log "ERROR: wal-g backup-push failed" >&2
    log "FAILURE" >&2
    exit 1
fi

duration=$(( $(date +%s) - start_ts ))
log "backup-push complete in ${duration}s"

# Best-effort: list backups so the log captures the resulting catalog.
# Failure here is non-fatal — the backup itself succeeded above.
log "current backup catalog (best-effort):"
docker exec "${walg_env[@]}" "${WALG_BACKUP_CONTAINER}" \
    "${WALG_BIN}" backup-list 2>&1 | sed "s/^/[walg-backup]   /" \
    || log "  (backup-list failed — non-fatal)"

log "SUCCESS"
exit 0
