import { Tooltip } from 'antd'
import { STATUS_LABEL, type ServiceHealth } from './useServiceHealth'

/**
 * DS-02 — compact 8-service health strip for the dashboard header.
 * Small dot + label per service. Dot colour = real UP/DOWN where actuator
 * exposes it (see useServiceHealth), grey when unknown.
 */
export default function ServiceHealthStrip({ services }: { services: ServiceHealth[] }) {
  return (
    <div className="ds-svc-strip" role="group" aria-label="Здоровье сервисов">
      <span className="ds-lbl">Сервисы</span>
      {services.map((s) => (
        <Tooltip
          key={s.id}
          title={`${s.realName} — ${STATUS_LABEL[s.status]}${
            s.real ? '' : ' (по агрегату)'
          } · uptime ${s.uptime}`}
        >
          <span className="ds-svc">
            <span className={`ds-dot ${s.status}`} />
            {s.name}
          </span>
        </Tooltip>
      ))}
    </div>
  )
}
