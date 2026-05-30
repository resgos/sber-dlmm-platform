import { Card, Tooltip } from 'antd'
import { STATUS_LABEL, type ServiceHealth } from './useServiceHealth'

/**
 * DS-02 — "Здоровье сервисов" detail card. 8 rows: status dot · name ·
 * latency (ms) · uptime %. Mirrors the mockup's svc-list. The mockup flags
 * KYC with a warn state; here whichever real service the actuator reports as
 * degraded/down lights up — the warn styling is data-driven, not hardcoded.
 *
 * Latency + uptime are representative (the health payload doesn't carry them);
 * the sub-title says so honestly.
 */
export default function ServiceHealthCard({
  services,
  reachable,
}: {
  services: ServiceHealth[]
  reachable: boolean
}) {
  const okCount = services.filter((s) => s.status === 'ok').length
  // How many dots come from a REAL per-service actuator probe vs inherited from
  // the gateway's aggregate status. When none are real, "N из M в норме" would
  // overstate (a downstream could be down while the gateway aggregate is UP) —
  // the dots only reflect the aggregate, so say so honestly.
  const realCount = services.filter((s) => s.real).length
  const subText = !reachable
    ? 'Actuator недоступен — статусы неизвестны'
    : realCount === 0
      ? 'Статус по агрегату шлюза — детальных проб по сервисам нет · uptime — оценка'
      : realCount === services.length
        ? `${okCount} из ${services.length} в норме · латентность/uptime — оценка`
        : `${okCount} из ${services.length} в норме · ${realCount} по детальным пробам, остальные по агрегату`
  return (
    <Card className="ds-card" styles={{ body: { padding: 0 } }}>
      <div className="ds-card-h">
        <div>
          <h3>Здоровье сервисов</h3>
          <div className="ds-sub">{subText}</div>
        </div>
      </div>
      <div style={{ padding: '4px 0 10px' }}>
        {services.map((s) => (
          <div className="ds-svc-row" key={s.id}>
            <Tooltip title={`${s.realName} — ${STATUS_LABEL[s.status]}${s.real ? '' : ' (по агрегату)'}`}>
              <span className={`ds-dot ${s.status}`} />
            </Tooltip>
            <span className="ds-svc-name">{s.name}</span>
            <span className="ds-svc-latency">{s.latency}</span>
            <span className="ds-svc-meta">uptime {s.uptime}</span>
          </div>
        ))}
      </div>
    </Card>
  )
}
