import { Card, Skeleton } from 'antd'

interface SkeletonCardProps {
  rows?: number
  title?: boolean
  /** Show the card border + padding (matches sber-card surface), or
   *  just the bare Skeleton без обёртки? */
  bare?: boolean
}

/**
 * UI-CRITIQUE 2026-05-22 #10 — single loading skeleton primitive.
 *
 * Before: React Query `isLoading` → null until data → пустой dashboard
 * на 1-2 секунды без feedback что "загрузка идёт". `Skeleton` использовался
 * только в PoolsPage cards. Other pages чёрно-белый молчат.
 *
 * After: convention — `{isLoading ? <SkeletonCard /> : data...}`
 * везде где React Query без data fallback.
 *
 * Defaults: 3 rows + title, wrapped в `sber-card` so it matches the
 * card's eventual appearance.
 */
export default function SkeletonCard({ rows = 3, title = true, bare = false }: SkeletonCardProps) {
  const inner = <Skeleton active paragraph={{ rows }} title={title} />
  if (bare) return inner
  return <Card className="sber-card">{inner}</Card>
}
