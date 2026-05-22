#!/usr/bin/env bash
# dr-drill.sh — automated Disaster Recovery drill для Sber DLMM (G-35).
#
# Sprint 11 G-35 (driven by Dmitry + АВ feedback): "тестируйте failover
# до того как мы подключаемся". Runs a snapshot → kill → restore →
# verify cycle and reports timing (RTO) + data-loss-window (RPO).
#
# Workflow:
#   1. Capture baseline row counts from 5 critical tables
#   2. Snapshot Postgres via pg-snapshot.sh
#   3. Stop postgres container (simulates total DB failure)
#   4. Start postgres again with empty volume (simulates fresh standby)
#   5. Restore from snapshot
#   6. Re-capture row counts; compare to baseline
#   7. Report: passed/failed + RTO timing + data-loss-window check
#
# IMPORTANT — drill is DESTRUCTIVE на named volume. Не запускать на
# production без read-only режима. Использовать только в staging /
# dev compose.
#
# Override через env:
#   DRILL_CONTAINER   = dlmm-postgres
#   DRILL_DB          = dlmm
#   DRILL_USER        = dlmm
#   DRILL_VOLUME      = docker_postgres-data (override если compose
#                       project name отличается)
#   DRILL_TABLES      = users tokens liquidity_pools lp_positions transactions
#   DRILL_SKIP_DESTROY=1 → DRY-RUN: только баseline + snapshot, без
#                          реальной остановки. Для CI smoke.
#
# Exit codes:
#   0  success — restored counts match baseline
#   1  row counts mismatch (data loss detected)
#   2  pre-flight failed (container not running, missing tools)
#   3  snapshot failed
#   4  restore failed

set -euo pipefail

DRILL_CONTAINER="${DRILL_CONTAINER:-dlmm-postgres}"
DRILL_DB="${DRILL_DB:-dlmm}"
DRILL_USER="${DRILL_USER:-dlmm}"
DRILL_VOLUME="${DRILL_VOLUME:-docker_postgres-data}"
DRILL_TABLES="${DRILL_TABLES:-users tokens liquidity_pools lp_positions transactions}"
DRILL_SKIP_DESTROY="${DRILL_SKIP_DESTROY:-0}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SNAPSHOT_SH="${SCRIPT_DIR}/pg-snapshot.sh"

# Anchor the backups dir explicitly to `docker/backups` next to the
# script — without this both scripts independently derive a path and
# can land in different directories depending on cwd.
DRILL_BACKUPS_DIR="${DRILL_BACKUPS_DIR:-$(cd "${SCRIPT_DIR}/.." && pwd)/backups}"
mkdir -p "${DRILL_BACKUPS_DIR}"
export SNAPSHOT_DIR="${DRILL_BACKUPS_DIR}"

log() {
    echo "[dr-drill] $(date -u +%H:%M:%S) $*"
}

# --- preflight --------------------------------------------------------
log "===== DR drill starting ====="
log "container=${DRILL_CONTAINER} db=${DRILL_DB} tables='${DRILL_TABLES}'"
if [[ "${DRILL_SKIP_DESTROY}" == "1" ]]; then
    log "DRILL_SKIP_DESTROY=1 → DRY-RUN mode (no container restart)"
fi

if ! command -v docker >/dev/null 2>&1; then
    log "ERROR: docker not on PATH" >&2
    exit 2
fi

if [[ -z "$(docker ps -q -f "name=^${DRILL_CONTAINER}$" 2>/dev/null || true)" ]]; then
    log "ERROR: container '${DRILL_CONTAINER}' is not running" >&2
    log "       (start with: docker-compose up -d postgres)" >&2
    exit 2
fi

if [[ ! -x "${SNAPSHOT_SH}" ]]; then
    log "ERROR: pg-snapshot.sh not found at ${SNAPSHOT_SH}" >&2
    exit 2
fi

# --- baseline row counts ----------------------------------------------
log "----- step 1: baseline row counts -----"
declare -A baseline
for table in $DRILL_TABLES; do
    count="$(docker exec "${DRILL_CONTAINER}" psql -U "${DRILL_USER}" -d "${DRILL_DB}" \
                -tAc "SELECT count(*) FROM ${table}" 2>/dev/null || echo "ERR")"
    if [[ "$count" == "ERR" ]]; then
        log "  ${table}: SKIPPED (does not exist on this DB?)"
        continue
    fi
    baseline["$table"]=$count
    printf "  %-25s %s rows\n" "${table}" "${count}"
done

if [[ ${#baseline[@]} -eq 0 ]]; then
    log "ERROR: no baseline rows captured — DB empty or tables missing" >&2
    exit 2
fi

# --- snapshot ---------------------------------------------------------
log "----- step 2: snapshot via pg-snapshot.sh -----"
snapshot_start_ms=$(date +%s%3N)
if ! "${SNAPSHOT_SH}"; then
    log "ERROR: snapshot failed" >&2
    exit 3
fi
snapshot_end_ms=$(date +%s%3N)
snapshot_duration_s=$(( (snapshot_end_ms - snapshot_start_ms) / 1000 ))
log "  snapshot took ${snapshot_duration_s}s"

# Find the latest dump file produced — we set SNAPSHOT_DIR above so
# pg-snapshot.sh wrote into our DRILL_BACKUPS_DIR.
latest_dump="$(ls -1t "${DRILL_BACKUPS_DIR}"/dlmm-*.dump 2>/dev/null | head -1 || true)"
if [[ -z "$latest_dump" || ! -f "$latest_dump" ]]; then
    log "ERROR: cannot locate latest snapshot in ${DRILL_BACKUPS_DIR}" >&2
    exit 3
fi
log "  latest dump: ${latest_dump}"

# --- DRY-RUN exit -----------------------------------------------------
if [[ "${DRILL_SKIP_DESTROY}" == "1" ]]; then
    log "DRY-RUN: skipping destroy / restore / verify"
    log "===== DR drill DONE (DRY-RUN) ====="
    exit 0
fi

# --- destroy + restart ------------------------------------------------
log "----- step 3: stop postgres container -----"
rto_start_ms=$(date +%s%3N)
docker stop "${DRILL_CONTAINER}" >/dev/null
log "  stopped"

log "----- step 4: remove volume + restart -----"
# NOTE: removing the volume is the most aggressive simulation of
# "fresh standby". Comment out if you want a softer test (just stop +
# restart with the same volume — postgres recovers automatically and
# the restore is unnecessary).
if docker volume inspect "${DRILL_VOLUME}" >/dev/null 2>&1; then
    docker volume rm "${DRILL_VOLUME}" >/dev/null
    log "  volume ${DRILL_VOLUME} removed"
else
    log "  WARN: volume ${DRILL_VOLUME} not found — skipping volume removal"
fi

# Start container — `docker start` re-creates the volume if compose
# defines it.
docker start "${DRILL_CONTAINER}" >/dev/null
log "  starting container; waiting for health…"

# Wait for postgres to accept connections.
attempts=0
until docker exec "${DRILL_CONTAINER}" pg_isready -U "${DRILL_USER}" -d "${DRILL_DB}" >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [[ $attempts -gt 60 ]]; then
        log "ERROR: postgres failed to come up within 60s" >&2
        exit 4
    fi
    sleep 1
done
log "  postgres is healthy (waited ${attempts}s)"

# --- restore ----------------------------------------------------------
log "----- step 5: restore from snapshot -----"
restore_start_ms=$(date +%s%3N)
if ! docker exec -i "${DRILL_CONTAINER}" pg_restore \
        -U "${DRILL_USER}" -d "${DRILL_DB}" \
        --clean --if-exists --no-owner --no-privileges \
        < "${latest_dump}" 2>&1 | tail -20; then
    log "ERROR: pg_restore failed" >&2
    exit 4
fi
restore_end_ms=$(date +%s%3N)
restore_duration_s=$(( (restore_end_ms - restore_start_ms) / 1000 ))
rto_total_s=$(( (restore_end_ms - rto_start_ms) / 1000 ))
log "  restore took ${restore_duration_s}s"

# --- verify -----------------------------------------------------------
log "----- step 6: verify row counts vs baseline -----"
failures=0
for table in $DRILL_TABLES; do
    if [[ -z "${baseline[$table]:-}" ]]; then continue; fi
    expected=${baseline[$table]}
    actual="$(docker exec "${DRILL_CONTAINER}" psql -U "${DRILL_USER}" -d "${DRILL_DB}" \
                -tAc "SELECT count(*) FROM ${table}" 2>/dev/null || echo "ERR")"
    if [[ "$actual" == "$expected" ]]; then
        printf "  ✓ %-25s %s rows (matches)\n" "${table}" "${actual}"
    else
        printf "  ✗ %-25s expected %s, got %s\n" "${table}" "${expected}" "${actual}"
        failures=$((failures + 1))
    fi
done

# --- report -----------------------------------------------------------
log "===== DR drill DONE ====="
log "  RTO (stop → restored): ${rto_total_s}s"
log "  Snapshot age at restore point ≈ 0s (we took fresh one); production RPO depends on backup cadence"
log "  Row-count parity:           $((${#baseline[@]} - failures))/${#baseline[@]} tables matched"

if [[ $failures -gt 0 ]]; then
    log "FAILED — $failures table(s) lost rows"
    exit 1
fi

log "PASSED — all baseline rows recovered"
exit 0
