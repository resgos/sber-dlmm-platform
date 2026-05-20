// Sprint 9 (post-DS-handoff) — shared DS primitives.
// Mirror of dlmm-admin-ui/src/components/sber/ (full cross-UI dedup
// requires a dlmm-ui-common workspace package; Sprint 10).
//
// UserChip is admin-only (it links to /users/:id which doesn't exist
// in the user UI).
export { default as KpiTile } from './KpiTile'
export type { KpiTileProps } from './KpiTile'
export { default as KpiRow } from './KpiRow'
export { default as TokenPairChip } from './TokenPairChip'
export { default as PageHeader } from './PageHeader'
