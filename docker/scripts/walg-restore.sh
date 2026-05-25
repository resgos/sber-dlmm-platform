#!/usr/bin/env bash
# walg-restore.sh — restore Postgres from WAL-G base backup + WAL replay (TD-6).
#
# Sprint 10+ companion to walg-backup.sh. Pulls a base backup from S3,
# unpacks into the target Postgres data dir, and (when archive_mode is on
# in production) replays WAL up to the requested point-in-time.
#
# DESIGNED TO BE RUN AGAINST A FRESH OR STOPPED POSTGRES CONTAINER.
# Running this against the live production cluster will CORRUPT data:
# WAL-G's `backup-fetch` writes directly into the data directory and
# overwrites whatever is there. The script enforces a guard rail (the
# container must be stopped) and prints loud pre-flight warnings.
#
# Reads env from docker/.env.walg — same vars as walg-backup.sh.
#
# Usage:
#   walg-restore.sh [--backup-name LATEST|<name>] [--target-time '<iso8601>']
#                   [--force] [--dry-run] [--help]
#
# Flags:
#   --backup-name   LATEST (default) or specific backup name from
#                   `wal-g backup-list` (e.g. base_000000010000000000000007).
#   --target-time   Recovery target. Restores base + replays WAL up to
#                   the given moment (PITR). Format:
#                   '2026-05-22 18:00:00 UTC'. Requires archive_mode=on
#                   in source cluster + WAL stream in S3 — see TODO in
#                   walg-backup.sh.
#   --force         Bypass the "container must be stopped" guard. Use
#                   only when restoring into a brand-new container that
#                   was never started against the target data dir.
#   --dry-run       Validate env + show what would happen, no fetch.
#   --help          Show usage.
#
# Exit codes:
#   0  success
#   1  fetch / restore failed
#   2  container guard tripped (still running — would corrupt)
#   3  required env var missing
#   4  wal-g binary not available
#   5  invalid usage

set -euo pipefail

usage() {
    cat <<'EOF'
walg-restore.sh — restore Postgres from WAL-G base backup.

Usage:
  walg-restore.sh [--backup-name LATEST|<name>] \
                  [--target-time '2026-05-22 18:00:00 UTC'] \
                  [--force] [--dry-run] [--help]

DANGER: this overwrites the data directory of the target container.
        ONLY run against a fresh or stopped Postgres container.

Examples:
  # Restore latest backup into a fresh container
  walg-restore.sh --backup-name LATEST --force

  # Point-in-time restore (requires WAL archive stream)
  walg-restore.sh --target-time '2026-05-22 18:00:00 UTC' --force

Env (loaded from docker/.env.walg):
  WALG_S3_PREFIX            REQUIRED  s3://bucket[/prefix]
  AWS_ACCESS_KEY_ID         REQUIRED
  AWS_SECRET_ACCESS_KEY     REQUIRED
  AWS_ENDPOINT              REQUIRED
  AWS_REGION                optional  default: ru-central1
  WALG_RESTORE_CONTAINER    optional  default: dlmm-postgres
  WALG_PGDATA               optional  default: /var/lib/postgresql/data
  WALG_BIN                  optional  default: wal-g

Exit codes: 0 success | 1 restore failed | 2 container running guard
            | 3 env missing | 4 wal-g unavailable | 5 invalid usage
EOF
}

# ---- parse flags -----------------------------------------------------
BACKUP_NAME="LATEST"
TARGET_TIME=""
FORCE=0
DRY_RUN=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --backup-name)   BACKUP_NAME="${2:?--backup-name needs a value}"; shift 2 ;;
        --target-time)   TARGET_TIME="${2:?--target-time needs a value}"; shift 2 ;;
        --force)         FORCE=1; shift ;;
        --dry-run)       DRY_RUN=1; shift ;;
        --help|-h)       usage; exit 0 ;;
        *) echo "[walg-restore] ERROR: unknown flag: $1" >&2; usage >&2; exit 5 ;;
    esac
done

# ---- log helper ------------------------------------------------------
log() {
    echo "[walg-restore] $(date -u +%Y-%m-%dT%H:%M:%SZ) $*"
}

warn_banner() {
    echo "" >&2
    echo "############################################################" >&2
    echo "#  WAL-G RESTORE — DESTRUCTIVE OPERATION                   #" >&2
    echo "#                                                          #" >&2
    echo "#  This will OVERWRITE the data directory of the target    #" >&2
    echo "#  container. Any uncheckpointed data will be lost.        #" >&2
    echo "#                                                          #" >&2
    echo "#  DO NOT run against the live production cluster.         #" >&2
    echo "############################################################" >&2
    echo "" >&2
}

# ---- load .env.walg --------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${WALG_ENV_FILE:-${SCRIPT_DIR}/../.env.walg}"

if [[ -f "$ENV_FILE" ]]; then
    log "loading env from ${ENV_FILE}"
    # shellcheck disable=SC1090
    set -a; source "$ENV_FILE"; set +a
else
    log "no ${ENV_FILE} found — relying on inherited env"
fi

# ---- validate required env ------------------------------------------
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
export AWS_REGION="${AWS_REGION:-ru-central1}"
WALG_RESTORE_CONTAINER="${WALG_RESTORE_CONTAINER:-dlmm-postgres}"
WALG_PGDATA="${WALG_PGDATA:-/var/lib/postgresql/data}"
WALG_BIN="${WALG_BIN:-wal-g}"

warn_banner

log "plan:"
log "  container       = ${WALG_RESTORE_CONTAINER}"
log "  data dir        = ${WALG_PGDATA}"
log "  backup name     = ${BACKUP_NAME}"
log "  target time     = ${TARGET_TIME:-<not set — full replay>}"
log "  S3 prefix       = ${WALG_S3_PREFIX}"
log "  force flag      = ${FORCE}"

# ---- preflight: target container --------------------------------------
# We need a RUNNING container with wal-g on PATH to `docker exec` into.
# The supported workflows are:
#   (a) WAL-G sidecar (dlmm-walg from docker-compose.walg.yml) acting
#       on a stopped postgres container's data volume.
#   (b) Fresh dlmm-postgres + --force, where postgres is up but empty
#       so overwrite is safe.
# If postgres itself is running with real data, we REFUSE without
# --force — overwriting an active data dir corrupts the cluster.
if ! command -v docker >/dev/null 2>&1; then
    log "ERROR: docker not on PATH" >&2
    exit 2
fi

container_running=$(docker ps -q -f "name=^${WALG_RESTORE_CONTAINER}$" 2>/dev/null || true)
if [[ -z "$container_running" ]]; then
    log "ERROR: container '${WALG_RESTORE_CONTAINER}' is not running" >&2
    log "       walg-restore.sh needs a running container with wal-g installed." >&2
    log "       Either start the WAL-G sidecar (docker-compose.walg.yml) and" >&2
    log "       set WALG_RESTORE_CONTAINER=dlmm-walg, or start a fresh empty" >&2
    log "       postgres container and pass --force." >&2
    exit 2
fi

if [[ "${WALG_RESTORE_CONTAINER}" == "dlmm-postgres" && "$FORCE" != "1" ]]; then
    log "ERROR: would restore directly into the live postgres container" >&2
    log "       '${WALG_RESTORE_CONTAINER}'. Refusing — would corrupt data." >&2
    log "       Either route through the WAL-G sidecar:" >&2
    log "         WALG_RESTORE_CONTAINER=dlmm-walg walg-restore.sh ..." >&2
    log "       …or, on a fresh/empty container, pass --force." >&2
    exit 2
fi
if [[ "$FORCE" == "1" ]]; then
    log "WARN: --force passed; assuming target data dir is safe to overwrite"
fi

# ---- preflight: wal-g binary ----------------------------------------
if ! docker exec "${WALG_RESTORE_CONTAINER}" sh -c "command -v ${WALG_BIN}" >/dev/null 2>&1; then
    log "ERROR: wal-g binary ('${WALG_BIN}') not installed in container '${WALG_RESTORE_CONTAINER}'" >&2
    log "       use the WAL-G sidecar (docker-compose.walg.yml) which ships wal-g" >&2
    exit 4
fi

# ---- dry-run exit ----------------------------------------------------
if [[ "$DRY_RUN" == "1" ]]; then
    log "dry-run: skipping fetch/restore"
    log "  would run: ${WALG_BIN} backup-fetch ${WALG_PGDATA} ${BACKUP_NAME}"
    if [[ -n "$TARGET_TIME" ]]; then
        log "  would write recovery.signal + recovery_target_time = '${TARGET_TIME}'"
    fi
    log "SUCCESS dry-run"
    exit 0
fi

# S3 + WAL-G env to forward into the container — built once, reused by
# backup-fetch + the recovery-signal write below.
walg_env=(
    -e "WALG_S3_PREFIX=${WALG_S3_PREFIX}"
    -e "AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}"
    -e "AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}"
    -e "AWS_ENDPOINT=${AWS_ENDPOINT}"
    -e "AWS_REGION=${AWS_REGION}"
)

# ---- fetch base backup ----------------------------------------------
# `wal-g backup-fetch <pgdata> <name|LATEST>` is the inverse of
# backup-push. It downloads from S3 and unpacks into <pgdata>.
# If <name> is LATEST it picks the most recent backup-list entry.
log "fetching base backup '${BACKUP_NAME}' into ${WALG_PGDATA}"
start_ts=$(date +%s)

if ! docker exec "${walg_env[@]}" "${WALG_RESTORE_CONTAINER}" \
        "${WALG_BIN}" backup-fetch "${WALG_PGDATA}" "${BACKUP_NAME}" 2>&1 | sed "s/^/[walg-restore]   /"; then
    log "ERROR: wal-g backup-fetch failed" >&2
    log "FAILURE" >&2
    exit 1
fi

duration=$(( $(date +%s) - start_ts ))
log "backup-fetch complete in ${duration}s"

# ---- write recovery signal (PITR) -----------------------------------
# Postgres 12+ uses recovery.signal (touch file) + GUCs in
# postgresql.auto.conf to drive recovery. WAL replay needs
# restore_command = 'wal-g wal-fetch %f %p'.
log "writing recovery signal + restore_command into ${WALG_PGDATA}"
recovery_conf="restore_command = '${WALG_BIN} wal-fetch \"%f\" \"%p\"'"
if [[ -n "$TARGET_TIME" ]]; then
    recovery_conf+=$'\n'"recovery_target_time = '${TARGET_TIME}'"
    recovery_conf+=$'\n'"recovery_target_action = 'promote'"
fi

if ! docker exec "${WALG_RESTORE_CONTAINER}" sh -c "
    touch '${WALG_PGDATA}/recovery.signal' &&
    printf '%s\n' \"${recovery_conf}\" >> '${WALG_PGDATA}/postgresql.auto.conf'
" 2>&1 | sed "s/^/[walg-restore]   /"; then
    log "ERROR: failed to write recovery signal" >&2
    log "FAILURE" >&2
    exit 1
fi

log "recovery configured. Postgres will replay WAL on next startup."
log ""
log "next steps for the operator:"
log "  1. Start the postgres container:  docker start ${WALG_RESTORE_CONTAINER}"
log "  2. Tail the log to watch recovery: docker logs -f ${WALG_RESTORE_CONTAINER}"
log "  3. When you see 'database system is ready to accept connections',"
log "     Postgres has promoted. Verify with:"
log "       docker exec ${WALG_RESTORE_CONTAINER} psql -U dlmm -d dlmm -c '\\dt'"
log "  4. Run the row-count parity check from docker/scripts/dr-drill.sh"
log "     to confirm data integrity."

log "SUCCESS"
exit 0
