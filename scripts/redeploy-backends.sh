#!/usr/bin/env bash
#
# redeploy-backends.sh — hot-redeploy backend services into the ALREADY-RUNNING
# Docker stack, WITHOUT a full `docker compose build` (slow + flaky tag race; see
# CLAUDE.md "Operational gotchas").
#
# For each requested service it:
#   1. builds the module fat-jar on the host (one reactor build for all of them),
#   2. `docker cp`s the jar into the container at /app/app.jar,
#   3. restarts the container,
#   4. polls /actuator/health until it reports "UP".
#
# This is the backend twin of scripts/redeploy-frontends.sh (which copies the UI
# dist). Re-run after EVERY `docker compose up` — a recreated container reverts to
# the jar baked into its image, dropping any hot-copied jar.
#
# Usage:
#   bash scripts/redeploy-backends.sh                          # all 9 services
#   bash scripts/redeploy-backends.sh pool-engine fee-service  # only these (dlmm- prefix optional)
#   SKIP_BUILD=1 bash scripts/redeploy-backends.sh user-service  # reuse the existing target/*.jar
#   MVN=/c/Tools/apache-maven-3.9.6/bin/mvn.cmd bash scripts/redeploy-backends.sh   # Windows mvn
#
set -euo pipefail

cd "$(dirname "$0")/.."   # repo root (script lives in ./scripts)

# Service container name -> host-mapped actuator health port.
declare -A PORT=(
  [dlmm-gateway]=8080
  [dlmm-user-service]=8081
  [dlmm-token-service]=8082
  [dlmm-pool-engine]=8083
  [dlmm-fee-service]=8084
  [dlmm-transaction-service]=8085
  [dlmm-price-oracle]=8086
  [dlmm-notification-service]=8087
  [dlmm-admin-bff]=8088
)
ALL=(dlmm-gateway dlmm-user-service dlmm-token-service dlmm-pool-engine \
     dlmm-fee-service dlmm-transaction-service dlmm-price-oracle \
     dlmm-notification-service dlmm-admin-bff)

MVN="${MVN:-mvn}"   # override on Windows: MVN=/c/Tools/apache-maven-3.9.6/bin/mvn.cmd

# ── Resolve requested services (default: all), normalising the dlmm- prefix ──────
REQUESTED=()
if [ "$#" -eq 0 ]; then
  REQUESTED=("${ALL[@]}")
else
  for arg in "$@"; do
    svc="$arg"; [[ "$svc" == dlmm-* ]] || svc="dlmm-$svc"
    if [[ -z "${PORT[$svc]:-}" ]]; then
      echo "✗ unknown service: '$arg' (known: ${ALL[*]})" >&2; exit 2
    fi
    REQUESTED+=("$svc")
  done
fi
echo "▶ services: ${REQUESTED[*]}"

# ── 1) Build the fat-jars (single reactor build for all requested modules + deps) ─
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  pl="$(IFS=,; echo "${REQUESTED[*]}")"
  echo "▶ building (mvn -pl $pl -am -DskipTests package) …"
  "$MVN" -q -pl "$pl" -am -DskipTests package
else
  echo "▶ SKIP_BUILD=1 — reusing existing target/*.jar"
fi

# ── 2) Copy jar → restart → wait for health, per service ─────────────────────────
for svc in "${REQUESTED[@]}"; do
  jar="$svc/target/$svc-1.0.0-SNAPSHOT.jar"
  [ -f "$jar" ]                                  || { echo "✗ missing $jar (build first, or unset SKIP_BUILD)" >&2; exit 3; }
  docker ps --format '{{.Names}}' | grep -qx "$svc" || { echo "✗ container $svc is not running (start the stack first)" >&2; exit 4; }

  echo "▶ $svc — cp jar + restart"
  docker cp "$jar" "$svc:/app/app.jar"
  docker restart "$svc" >/dev/null

  port="${PORT[$svc]}"
  printf "  waiting for :%s/actuator/health " "$port"
  for i in $(seq 1 30); do
    if curl -fs -m 3 "localhost:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      echo "✓ UP"; break
    fi
    printf "."
    if [ "$i" -eq 30 ]; then
      echo " ✗ not UP after 60s — inspect: docker logs --tail=80 $svc" >&2; exit 5
    fi
    sleep 2
  done
done

echo "✓ redeploy complete: ${REQUESTED[*]}"
echo "  (OpenAPI docs now live at each service's /swagger-ui.html and /v3/api-docs)"
