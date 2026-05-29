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
  # NB: `docker cp dist/.  c:/path/` is unreliable — in some docker versions
  # it creates a `dist/` SUBdir instead of copying contents (broke the demo
  # once: nginx 403, index.html missing). Robust pattern: cp the dir to /tmp,
  # then `cp -a .../.` the CONTENTS into the html root inside the container.
  docker cp "$ROOT/$dir/dist" "$container:/tmp/freshdist"
  docker exec "$container" sh -c \
    'rm -rf /usr/share/nginx/html/* && cp -a /tmp/freshdist/. /usr/share/nginx/html/ && rm -rf /tmp/freshdist'
  # Verify from INSIDE the container (ground truth, not local ls).
  local served
  served="$(docker exec "$container" sh -c 'ls /usr/share/nginx/html/assets/index-*.js 2>/dev/null | head -1 | xargs basename')"
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$([ "$container" = dlmm-admin-ui ] && echo 3000 || echo 3001)/")"
  echo "   served: ${served:-MISSING}  (HTTP $code)"
  [ "$code" = "200" ] || echo "   ⚠️ NON-200 — check container!"
}

deploy dlmm-user-ui  dlmm-user-ui
deploy dlmm-admin-ui dlmm-admin-ui
echo "✅ Both frontends redeployed from current source. Hard-refresh browser (Ctrl+Shift+R)."
