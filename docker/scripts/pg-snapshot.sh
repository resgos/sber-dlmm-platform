#!/usr/bin/env bash
# pg-snapshot.sh — Postgres snapshot helper for Sber DLMM (TD-6).
#
# Sprint 9-DS-r4. Dev-grade nightly snapshot — see
# docs/OPS-DB-BACKUP-RUNBOOK.md for the production replacement
# story (WAL-G + S3, Sprint 10).
#
# Defaults:
#   container = dlmm-postgres
#   db        = dlmm
#   user      = dlmm
#   target    = ./docker/backups/dlmm-<ts>.dump
#   retention = 14 days (prunes older dumps)
#
# Override via env: SNAPSHOT_CONTAINER, SNAPSHOT_DB, SNAPSHOT_USER,
#                   SNAPSHOT_DIR, SNAPSHOT_RETENTION_DAYS.
#
# Exit codes: 0 success | 1 dump failed | 2 container not running.
#
# Designed to be cron-runnable; logs to stdout/stderr so cron MTA
# captures any errors. Sample cron line:
#   17 3 * * * /opt/dlmm/docker/scripts/pg-snapshot.sh >> /var/log/dlmm-snapshot.log 2>&1

set -euo pipefail

SNAPSHOT_CONTAINER="${SNAPSHOT_CONTAINER:-dlmm-postgres}"
SNAPSHOT_DB="${SNAPSHOT_DB:-dlmm}"
SNAPSHOT_USER="${SNAPSHOT_USER:-dlmm}"
SNAPSHOT_DIR="${SNAPSHOT_DIR:-$(cd "$(dirname "$0")/../backups" 2>/dev/null && pwd || echo "$(pwd)/docker/backups")}"
SNAPSHOT_RETENTION_DAYS="${SNAPSHOT_RETENTION_DAYS:-14}"

ts="$(date -u +%Y-%m-%dT%H%M%SZ)"
target="${SNAPSHOT_DIR}/dlmm-${ts}.dump"

log() {
    echo "[pg-snapshot] $*"
}

# Pre-flight: verify container is running. `docker ps -q -f name=...`
# returns the container id if running, empty otherwise — easier to
# match than `docker inspect` error strings.
if [[ -z "$(docker ps -q -f "name=^${SNAPSHOT_CONTAINER}$" 2>/dev/null || true)" ]]; then
    log "ERROR: container '${SNAPSHOT_CONTAINER}' is not running" >&2
    log "       (start with: docker-compose up -d postgres)" >&2
    exit 2
fi

mkdir -p "${SNAPSHOT_DIR}"

log "starting at ${ts}"
log "dump format=custom, target=${target}"

# pg_dump --format=custom is the right format for pg_restore --clean
# --if-exists workflows. -Z 6 compresses with zlib level 6 (good
# space/speed trade-off; -Z 9 is only 10% smaller but ~3× slower).
# -j is NOT used here — parallel dump requires --format=directory,
# which is harder to ship around. At our scale (~4 MB today, ~200 MB
# projected) single-threaded -Z 6 finishes in <2s.
if ! docker exec "${SNAPSHOT_CONTAINER}" \
        pg_dump -U "${SNAPSHOT_USER}" -d "${SNAPSHOT_DB}" \
                --format=custom -Z 6 \
        > "${target}.partial"; then
    log "ERROR: pg_dump failed" >&2
    rm -f "${target}.partial"
    exit 1
fi

# Atomic rename — never expose a half-written file as a "snapshot".
mv "${target}.partial" "${target}"

size="$(du -h "${target}" | awk '{print $1}')"
log "dump complete (${size})"

# Retention prune. -mtime +N matches files modified > N days ago.
# Using `find -delete` rather than `xargs rm` so empty pruning is
# zero-cost and we don't accidentally rm . if the find finds nothing.
pruned=$(find "${SNAPSHOT_DIR}" -maxdepth 1 -type f -name 'dlmm-*.dump' \
              -mtime "+${SNAPSHOT_RETENTION_DAYS}" -print -delete 2>/dev/null | wc -l)
log "pruned ${pruned} dumps older than ${SNAPSHOT_RETENTION_DAYS} days"

exit 0
