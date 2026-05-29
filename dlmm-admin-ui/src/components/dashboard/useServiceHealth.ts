import { useQuery } from '@tanstack/react-query'

/**
 * DS-02 — service-health hook.
 *
 * Fetches Spring Boot Actuator health JSON via the gateway (the vite dev
 * proxy maps `/actuator` → gateway:8080; in prod nginx proxies the same).
 * We bypass the axios `apiClient` on purpose — its baseURL is `/api/v1`, and
 * the actuator endpoint sits at the host root. Same approach as
 * ApiAnalyticsPage's `/actuator/prometheus` scrape.
 *
 * The 8 strip labels are a FIXED ops-facing taxonomy from the mockup. Each
 * maps to one of our real microservices (CLAUDE.md service map). Real UP/DOWN
 * comes from whatever the actuator exposes:
 *   • Boot exposes downstream probes under `components.{name}` when
 *     `show-details: always` (admin-bff registers userService/tokenService/
 *     poolEngine/transactionService/feeService — see admin-bff HealthConfig).
 *   • If a specific component isn't present, we fall back to the OVERALL
 *     status so the dot still reflects reality at the aggregate level rather
 *     than lying green.
 *   • If the whole fetch fails, every dot is 'unknown' (grey) — we never
 *     fabricate UP.
 *
 * Latency/uptime are NOT in the health payload, so they're representative
 * (deterministic per service) and labelled as such in the UI sub-text.
 */

export type SvcStatus = 'ok' | 'warn' | 'err' | 'unknown'

export interface ServiceHealth {
  id: string
  /** Strip label (mockup taxonomy). */
  name: string
  /** Real microservice this maps to (for the tooltip). */
  realName: string
  status: SvcStatus
  /** Representative latency string, e.g. "22мс". */
  latency: string
  /** Representative uptime string, e.g. "99,98%". */
  uptime: string
  /** True when `status` came from a real per-service actuator component
   *  (vs. inherited from the aggregate status or unknown). */
  real: boolean
}

interface HealthPayload {
  status?: string
  components?: Record<string, { status?: string }>
  details?: Record<string, { status?: string }>
}

/** Fixed strip taxonomy → (real microservice, fuzzy actuator-component keys,
 *  representative latency/uptime). */
const SERVICE_MAP: Array<{
  id: string
  name: string
  realName: string
  /** Lowercased substrings to match against actuator component keys. */
  match: string[]
  latency: string
  uptime: string
}> = [
  { id: 'gateway', name: 'API Gateway', realName: 'dlmm-gateway', match: ['gateway'], latency: '14мс', uptime: '99,98%' },
  { id: 'pool', name: 'Pool Engine', realName: 'dlmm-pool-engine', match: ['poolengine', 'pool'], latency: '22мс', uptime: '99,97%' },
  { id: 'tx', name: 'Транзакции', realName: 'dlmm-transaction-service', match: ['transactionservice', 'transaction'], latency: '31мс', uptime: '99,95%' },
  { id: 'oracle', name: 'Oracle Feed', realName: 'dlmm-price-oracle', match: ['priceoracle', 'oracle', 'price'], latency: '46мс', uptime: '99,90%' },
  { id: 'kyc', name: 'KYC / Users', realName: 'dlmm-user-service', match: ['userservice', 'user'], latency: '38мс', uptime: '99,93%' },
  { id: 'token', name: 'Token Service', realName: 'dlmm-token-service', match: ['tokenservice', 'token'], latency: '24мс', uptime: '99,96%' },
  { id: 'fee', name: 'Fee Service', realName: 'dlmm-fee-service', match: ['feeservice', 'fee'], latency: '19мс', uptime: '99,99%' },
  { id: 'notify', name: 'Notify', realName: 'dlmm-notification-service', match: ['notification', 'notify', 'kafka'], latency: '18мс', uptime: '99,94%' },
]

function springToStatus(s?: string): SvcStatus {
  switch ((s ?? '').toUpperCase()) {
    case 'UP':
      return 'ok'
    case 'DOWN':
    case 'OUT_OF_SERVICE':
      return 'err'
    case 'UNKNOWN':
    case '':
      return 'unknown'
    default:
      // Any non-UP, non-DOWN value (e.g. a custom "DEGRADED") → warn.
      return 'warn'
  }
}

export function mapHealth(payload: HealthPayload | null): ServiceHealth[] {
  const overall = springToStatus(payload?.status)
  const components = payload?.components ?? payload?.details ?? {}
  const compEntries = Object.entries(components).map(([k, v]) => ({
    key: k.toLowerCase(),
    status: springToStatus(v?.status),
  }))

  return SERVICE_MAP.map((svc) => {
    const hit = compEntries.find((c) => svc.match.some((m) => c.key.includes(m)))
    let status: SvcStatus
    let real: boolean
    if (hit) {
      status = hit.status
      real = true
    } else if (payload) {
      // No per-service component, but we DID reach actuator → inherit the
      // aggregate so we don't claim more than we know.
      status = overall === 'unknown' ? 'unknown' : overall
      real = false
    } else {
      status = 'unknown'
      real = false
    }
    return {
      id: svc.id,
      name: svc.name,
      realName: svc.realName,
      status,
      latency: svc.latency,
      uptime: svc.uptime,
      real,
    }
  })
}

async function fetchHealth(): Promise<HealthPayload | null> {
  try {
    const resp = await fetch('/actuator/health', { headers: { Accept: 'application/json' } })
    // Spring returns 503 with a body when DOWN — still parse it.
    const text = await resp.text()
    if (!text) return resp.ok ? {} : null
    try {
      return JSON.parse(text) as HealthPayload
    } catch {
      return resp.ok ? {} : null
    }
  } catch {
    return null
  }
}

export function useServiceHealth() {
  const query = useQuery({
    queryKey: ['actuator-health'],
    queryFn: fetchHealth,
    refetchInterval: 15_000,
    staleTime: 10_000,
    retry: 0,
  })
  const services = mapHealth(query.data ?? null)
  // Aggregate uptime is representative; the count of degraded/down is real
  // wherever component data exists.
  const degraded = services.filter((s) => s.status === 'warn' || s.status === 'err').length
  return { services, degraded, reachable: query.data != null, isLoading: query.isLoading }
}

export const STATUS_LABEL: Record<SvcStatus, string> = {
  ok: 'В норме',
  warn: 'Задержки',
  err: 'Сбой',
  unknown: 'Нет данных',
}
