import { Row, Col } from 'antd'
import KpiTile, { type KpiTileProps } from './KpiTile'

/**
 * Sprint 9 (post-DS-handoff) — 4-up KPI tile row.
 *
 * <p>Responsive: 4 tiles on lg+, 2 on sm, 1 on xs. Matches the OTC desk
 * layout exactly. Most admin list pages need exactly 3-4 KPIs; pass as
 * an array — if fewer than 4, columns auto-distribute.
 */
export interface KpiRowProps {
  tiles: KpiTileProps[]
}

export default function KpiRow({ tiles }: KpiRowProps) {
  // 12-col grid: 4 tiles → 6 each on sm, 6 on md, 6 on lg. 3 → 8/12 on lg.
  // We default to 6 on lg (= 4 in a row); pages with 3 KPIs can wrap.
  const lgSpan = tiles.length <= 3 ? 8 : 6
  return (
    <Row gutter={[16, 16]}>
      {tiles.map((t, i) => (
        <Col key={`${t.label}-${i}`} xs={24} sm={12} lg={lgSpan}>
          <KpiTile {...t} />
        </Col>
      ))}
    </Row>
  )
}
