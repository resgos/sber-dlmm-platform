#!/usr/bin/env bash
# redeploy-frontends.sh — guaranteed-fresh frontend deploy.
#
# WHY THIS EXISTS (demo review 2026-05-27):
# In this environment `docker-compose build [--no-cache] dlmm-{user,admin}-ui`
# intermittently fails to produce a NEW image — the served bundle stays at an
# older hash despite source changes and exit code 0 (flaky Docker daemon /
# BuildKit tag race under the sandbox). Symptom: new pages (e.g. admin
# /pilots, Cohorts sidebar) 404→redirect, KPI tooltips missing, etc.
#
# This script sidesteps the image build entirely: build the Vite dist on the
# host (fast, deterministic) and `docker cp` it straight into the running
# nginx container. nginx serves it immediately. Run after every
# `docker-compose up` (the cp is lost on container recreate).
#
# Usage: bash scripts/redeploy-frontends.sh   (from repo/worktree root)

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

deploy() {
  local dir="$1" container="$2"
  echo "── $dir → $container ──"
  ( cd "$ROOT/$dir" && rm -rf dist && npm run build >/dev/null 2>&1 )
  local hash
  hash="$(ls "$ROOT/$dir"/dist/assets/index-*.js | head -1 | xargs basename)"
  docker exec "$container" sh -c 'rm -rf /usr/share/nginx/html/assets /usr/share/nginx/html/index.html'
  docker cp "$ROOT/$dir/dist/." "$container:/usr/share/nginx/html/"
  echo "   served: $hash"
}

deploy dlmm-user-ui  dlmm-user-ui
deploy dlmm-admin-ui dlmm-admin-ui
echo "✅ Both frontends redeployed from current source. Hard-refresh browser (Ctrl+Shift+R)."
