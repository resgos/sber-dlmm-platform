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
  return (
    <Card className="ds-card" styles={{ body: { padding: 0 } }}>
      <div className="ds-card-h">
        <div>
          <h3>Здоровье сервисов</h3>
          <div className="ds-sub">
            {reachable
              ? `${okCount} из ${services.length} в норме · латентность/uptime — оценка`
              : 'Actuator недоступен — статусы неизвестны'}
          </div>
        </div>
      </div>
      <div style={{ padding: '4px 0 10px' }}>
        {services.map((s) => (
          <div className="ds-svc-row" key={s.id}>
            <Tooltip title={`${s.realName} — ${STATUS_LABEL[s.status]}`}>
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
