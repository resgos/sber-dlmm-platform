# UI shared-code register (P2-17)

> Sprint 9-DS-r4. Backlog P2-17 in `docs/BACKLOG-2026-05-21.md`.
>
> The admin-ui and user-ui share several UI atoms and helper modules
> by copy. This file is the manifest of what's duplicated, why we
> haven't yet hoisted them into a workspace package, and what the
> migration plan looks like.

---

## Why not a workspace today

The proper fix is a yarn / pnpm / npm workspace package
(`packages/dlmm-ui-common/`) that both UIs depend on. That refactor
needs:

1. A monorepo workspace root (`package.json` workspaces field) —
   doesn't exist today; each UI has its own `package-lock.json`.
2. Build-tool wiring — Vite needs to resolve the package via
   `tsconfig.json` paths *and* via `vite.config.ts` aliases, and
   both UIs need to agree on the shape (esm vs cjs, peer deps for
   React / AntD).
3. CI updates — `npm ci` per UI becomes `npm ci` at root +
   workspace install.
4. Type provenance — `dlmm-common-types` for backend DTO mirrors
   would be a sibling package, freeing both UIs from maintaining
   their own `src/api/types.ts`.

That's a Sprint-10-sized L-effort refactor, and the migration
itself is risky on the current branch where every UI page is in
flux. **The drift watchdog below is the tactical alternative until
the workspace refactor lands.**

## Tactical control: drift watchdog

`scripts/check-ui-shared-drift.mjs` runs in CI (`frontend.yml`
right after hex-ratchet). The script maintains a manifest of
known-shared files and fails the build if a `strict: true` entry
diverges between admin-ui and user-ui at byte level.

Example output:
```
[ui-drift] OK       lib/format.ts
[ui-drift] OK       utils/format.ts
[ui-drift] OK       components/sber/TokenPairChip.tsx
[ui-drift] DIVERGE  components/sber/KpiTile.tsx  (relaxed — documented)
[ui-drift] checked 4 entries — 0 drift, 1 documented divergence
```

When you intentionally diverge a file, either:
- Copy the change to the other UI (most common — the file is
  *supposed* to be shared), or
- Promote the manifest entry to `strict: false` with a one-liner
  explaining why divergence is now permanent.

CRLF-vs-LF is normalised before comparison so Windows checkouts
don't false-trip.

## Current manifest

| File | Strict | Why divergence (if any) |
|---|---|---|
| `lib/format.ts` | yes | Pure formatter helpers (formatCompact / formatTokenAmount). Workspace target: `packages/dlmm-ui-common/src/format.ts`. |
| `utils/format.ts` | yes | Legacy formatter location (bpsToPercent / formatBinStep). Same workspace target. |
| `components/sber/TokenPairChip.tsx` | yes | Visual atom for token-pair pills (SRUB/SBER). No app-specific props. |
| `components/sber/KpiTile.tsx` | no | user-ui widens `sub` prop to `ReactNode` (P2-4 — multi-line "Моя доля" sub); admin still uses string-only. Copy from user-ui to admin when admin needs the wider type. |

### Adding to the manifest

When you find a third file that should be tracked, edit
`scripts/check-ui-shared-drift.mjs` `SHARED_FILES` array and pick
strict / relaxed. No CI config change needed — the script already
runs on every PR.

## Migration plan to workspace

1. **Pre-flight** (no breakage): copy the manifest's strict files
   into `packages/dlmm-ui-common/src/` so the source-of-truth
   lives in one place even before consumers switch.
2. **Workspaces root**: top-level `package.json` with
   `"workspaces": ["packages/*", "dlmm-admin-ui", "dlmm-user-ui"]`.
3. **Per-UI swap**: `dlmm-admin-ui/src/lib/format.ts` becomes a
   single line: `export * from '@dlmm/ui-common/format'`. Same for
   the user side.
4. **CI**: replace per-UI `npm ci` with workspace install. Vitest
   per UI still works — packages are dev deps inside the
   workspace.
5. **Drift watchdog**: shrinks to just the relaxed entries
   (intentional divergence) since the strict files are
   re-exports of the package.

Effort estimate: 4-6 days (the trickiest piece is the AntD peer
dep — both UIs pin antd@5.x but with different patch versions
today; package.json deduplication is the hidden tax).
