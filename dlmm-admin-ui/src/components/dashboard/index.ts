// DS-02 — admin dashboard ("Обзор") building blocks. Faithful port of
// docs/design/admin-dashboard-claude-design/.
export { default as Sparkline } from './Sparkline'
export { default as DeltaPill } from './DeltaPill'
export { default as DashKpiTile } from './DashKpiTile'
export { default as TvlAreaChart } from './TvlAreaChart'
export { default as ServiceHealthStrip } from './ServiceHealthStrip'
export { default as ServiceHealthCard } from './ServiceHealthCard'
export { useServiceHealth, mapHealth, STATUS_LABEL } from './useServiceHealth'
export type { ServiceHealth, SvcStatus } from './useServiceHealth'
